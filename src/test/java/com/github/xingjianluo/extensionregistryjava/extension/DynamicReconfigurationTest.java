package com.github.xingjianluo.extensionregistryjava.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行期动态新增/下线测试：参与装配、彻底下线、依赖保护、未受影响扩展不重复初始化、
 * 移除再加回语义、顺序稳定、失败整体回滚与并发可见性。
 */
class DynamicReconfigurationTest {

    private static final Logger log = LoggerFactory.getLogger(DynamicReconfigurationTest.class);

    private static RecordingExtension ext(String id, int order, String... deps) {
        return new RecordingExtension(id, "p", order, Set.of(deps), false);
    }

    private static RecordingExtension failing(String id, int order, String... deps) {
        return new RecordingExtension(id, "p", order, Set.of(deps), true);
    }

    private static List<String> ids(ExtensionRegistry registry) {
        return registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList();
    }

    private static void logStep(String step, ExtensionRegistry registry) {
        log.info("==== 动态调整步骤[{}] 装配顺序与判定依据 | 当前顺序={} ====", step, ids(registry));
        for (AssemblyRecord r : registry.getLastAssemblyReport().records()) {
            log.info("装配位[{}] id={} declaredOrder={} dependencies={} state={} enabled={} | {}",
                    r.index(), r.id(), r.declaredOrder(), r.dependencies(), r.state(),
                    registry.isEnabled(r.id()), r.reason());
        }
    }

    @Test
    void addedExtensionsParticipateInAssemblyAndBecomeCallable() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension base = ext("base", 100);
        registry.register(base);

        // 两个新扩展同批加入：vip 依赖 base；nova 无依赖但声明顺序最小。
        RecordingExtension vip = ext("vip", 50, "base");
        RecordingExtension nova = ext("nova", 10);
        registry.addAll(List.of(vip, nova));
        logStep("新增vip+nova", registry);

        // 依赖优先 + 声明顺序：nova(10) 无约束最小；base 先于 vip => nova, base, vip
        assertEquals(List.of("nova", "base", "vip"), ids(registry));
        assertEquals(1, base.initCount());
        assertEquals(1, vip.initCount());
        assertEquals(1, nova.initCount());

        List<String> values = ExtensionInvoker.invokeEnabled(registry, "p", "Q");
        assertEquals(List.of("nova:Q", "base:Q", "vip:Q"), values);
    }

    @Test
    void removedExtensionDisappearsFromEveryView() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension a = ext("a", 1);
        RecordingExtension b = ext("b", 2);
        RecordingExtension c = ext("c", 3);
        registry.registerAll(List.of(a, b, c));

        registry.remove("b");
        logStep("下线b", registry);

        assertEquals(List.of("a", "c"), ids(registry));
        assertEquals(List.of("a", "c"),
                registry.getEnabledExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertNull(registry.getDescriptor("b"));
        assertNull(registry.getExtension("b"));
        assertFalse(registry.isEnabled("b")); // 已下线：开关位随之消失
        assertTrue(registry.getLastAssemblyReport().records().stream()
                .noneMatch(r -> r.id().equals("b")));

        List<ExtensionResult<String>> results = ExtensionInvoker.invokeAll(registry, "p", "X");
        assertEquals(List.of("a", "c"), results.stream().map(ExtensionResult::extensionId).toList());
        assertEquals(0, b.executeCount()); // 下线后绝不可能再被调用
    }

    @Test
    void removingExtensionStillInUseIsRejectedAtomically() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension base = ext("base", 1);
        RecordingExtension vip = ext("vip", 2, "base");
        registry.registerAll(List.of(base, vip));

        ExtensionInUseException ex = assertThrows(ExtensionInUseException.class,
                () -> registry.remove("base"));
        log.info("被依赖扩展下线被拒绝: {}，明细: {}", ex.getMessage(), ex.blockedBy());

        // 视图完全不变：base 仍可查可调用，装配报告也不变。
        assertEquals(List.of("base", "vip"), ids(registry));
        assertEquals(List.of("vip"), ex.blockedBy().get("base"));
        assertNotNull(registry.getDescriptor("base"));
        assertEquals(1, base.initCount());

        // 批量场景：只要有存活扩展（v2）仍依赖被下线集合中的 base，就整体拒绝，不做部分下线。
        registry.add(ext("v2", 3, "base"));
        ExtensionInUseException batchEx = assertThrows(ExtensionInUseException.class,
                () -> registry.removeAll(List.of("vip", "base")));
        log.info("批量下线含被依赖项被拒绝: {}", batchEx.blockedBy());
        assertEquals(List.of("v2"), batchEx.blockedBy().get("base"));
        assertEquals(List.of("base", "vip", "v2"), ids(registry));

        // 先下线全部依赖方后，被依赖方才可下线；一次批量同时下线 base+v2 允许
        // （它们一起消失，存活集合中不再存在指向 base 的依赖）。
        registry.removeAll(List.of("vip", "base", "v2"));
        logStep("批量同时下线全部相关扩展", registry);
        assertEquals(List.of(), ids(registry));
    }

    @Test
    void removingUnknownIdIsRejectedAndViewStays() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(ext("a", 1));
        assertThrows(IllegalArgumentException.class, () -> registry.remove("ghost"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.removeAll(List.of("a", "ghost")));
        assertEquals(List.of("a"), ids(registry));
    }

    @Test
    void invalidAddIsRejectedWithOldViewIntact() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerAll(List.of(ext("a", 1)));

        assertThrows(DuplicateExtensionException.class, () -> registry.add(ext("a", 9)));
        assertThrows(DanglingDependencyException.class,
                () -> registry.add(ext("d", 2, "missing")));
        registry.register(ext("b", 2));
        assertThrows(DependencyCycleException.class,
                () -> registry.add(ext("cyc", 3, "cyc")));
        assertThrows(DependencyCycleException.class,
                () -> registry.addAll(List.of(ext("x", 3, "y"), ext("y", 4, "x"))));

        logStep("四次非法新增均被拒绝后", registry);
        // 调整前视图照常可用：只有最初的 a、b。
        assertEquals(List.of("a", "b"), ids(registry));
        assertEquals(List.of("a:Z", "b:Z"),
                ExtensionInvoker.invokeEnabled(registry, "p", "Z"));
    }

    @Test
    void failedInitOfNewExtensionRollsBackWholeBatch() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension a = ext("a", 1);
        registry.register(a);

        RecordingExtension good = ext("good", 2);
        RecordingExtension bad = failing("bad", 3);
        DynamicReconfigurationException ex = assertThrows(DynamicReconfigurationException.class,
                () -> registry.addAll(List.of(good, bad)));
        log.info("新扩展初始化失败导致整批回滚: {}", ex.getMessage());
        assertEquals("bad", ex.failedExtensionId());

        // good 即便已经初始化过，也因快照未发布而不可见、被整体丢弃。
        assertEquals(List.of("a"), ids(registry));
        assertNull(registry.getDescriptor("good"));
        assertNull(registry.getDescriptor("bad"));
        // 扩展点未瘫痪：调整前视图照常可调用。
        assertEquals(List.of("a:Z"),
                ExtensionInvoker.invokeEnabled(registry, "p", "Z"));

        // 回滚后可以用一组健康的扩展重新调整成功。
        registry.add(ext("ok", 2));
        assertEquals(List.of("a", "ok"), ids(registry));
    }

    @Test
    void unaffectedExtensionsKeepInstanceInitAndEnabledState() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension a = ext("a", 1);
        RecordingExtension b = ext("b", 2);
        registry.registerAll(List.of(a, b));
        registry.setEnabled("b", false); // 先制造一个"设置过的启用状态"

        // 新增其它扩展：a/b 不重新初始化，b 仍是禁用。
        registry.add(ext("c", 3));
        logStep("新增c后", registry);
        assertSame(a, registry.getExtension("a"));
        assertSame(b, registry.getExtension("b"));
        assertEquals(1, a.initCount());
        assertEquals(1, b.initCount());
        assertFalse(registry.isEnabled("b"));

        // 下线新扩展：a/b 依旧不重新初始化，b 依旧禁用。
        registry.remove("c");
        assertSame(a, registry.getExtension("a"));
        assertSame(b, registry.getExtension("b"));
        assertEquals(1, a.initCount());
        assertEquals(1, b.initCount());
        assertFalse(registry.isEnabled("b"));
        assertEquals(List.of("a", "b"), ids(registry));
    }

    @Test
    void removeThenReAddCreatesFreshInstanceWithResetState() {
        // 语义定死：移除即彻底丢弃实例与状态（含 enabled）；再加回 = 新实例、重新初始化、enabled 重置默认。
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension first = ext("svc", 1);
        registry.register(first);
        registry.setEnabled("svc", false);
        registry.remove("svc");
        assertNull(registry.getDescriptor("svc"));
        assertEquals(1, first.initCount(), "旧实例移除后不会被再次初始化");

        RecordingExtension second = ext("svc", 1);
        registry.add(second);
        logStep("同id移除后再加回", registry);

        assertSame(second, registry.getExtension("svc"));
        assertEquals(1, first.initCount(), "旧实例始终只初始化一次");
        assertEquals(1, second.initCount(), "新实例重新初始化一次");
        assertTrue(registry.isEnabled("svc"), "再加回后 enabled 重置为默认启用，无禁用状态残留");
        assertEquals(1, ids(registry).size(), "同一标识任何时刻只有一个实例");
    }

    @Test
    void assemblyOrderAfterChurnMatchesStaticAssemblyOfFinalSet() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerAll(List.of(
                ext("keep", 50),
                ext("dep", 20),
                ext("dependent", 30, "dep")));
        // 动态搅动：插入中间 order 的扩展再下线，再新增带依赖的扩展。
        registry.add(ext("middle", 25));
        registry.remove("middle");
        registry.add(ext("late", 40, "dep"));
        logStep("搅动完成后", registry);

        List<String> dynamicOrder = ids(registry);

        // 对照：最终集合用纯静态方式、以完全不同的注册到达顺序再装一次，结果必须一致。
        ExtensionRegistry staticReference = new ExtensionRegistry();
        staticReference.registerAll(List.of(
                ext("late", 40, "dep"),
                ext("dependent", 30, "dep"),
                ext("keep", 50),
                ext("dep", 20)));
        assertEquals(ids(staticReference), dynamicOrder,
                "动态调整后的顺序必须与静态装配同一套确定性规则的结果一致");

        // 显式断言关键判定：dep 先于两个依赖方；无依赖的 keep 与 dep 间按声明顺序 dep(20) 在前。
        assertEquals(List.of("dep", "dependent", "late", "keep"), dynamicOrder);
    }

    @Test
    void scriptedScenarioProducesIdenticalResultsAcrossTwoRuns() {
        List<List<String>> run1 = runScriptedScenario();
        List<List<String>> run2 = runScriptedScenario();
        log.info("同一脚本连跑两遍顺序轨迹 1={}", run1);
        log.info("同一脚本连跑两遍顺序轨迹 2={}", run2);
        assertEquals(run1, run2, "连续跑两遍，每步装配顺序必须完全一致");
    }

    /** 固定的增/删/开关脚本，返回每一步完成后的装配顺序轨迹。 */
    private static List<List<String>> runScriptedScenario() {
        ExtensionRegistry registry = new ExtensionRegistry();
        List<List<String>> trace = new ArrayList<>();
        registry.registerAll(List.of(ext("a", 30), ext("b", 10), ext("d", 20)));
        trace.add(ids(registry));
        registry.add(ext("x", 5, "d"));
        trace.add(ids(registry));
        registry.setEnabled("b", false);
        trace.add(ids(registry));
        registry.remove("x");
        trace.add(ids(registry));
        registry.add(ext("y", 15, "b"));
        trace.add(ids(registry));
        registry.removeAll(List.of("y", "b"));
        trace.add(ids(registry));
        return trace;
    }

    @Test
    void concurrentAdjustmentsAndCallsNeverExposePartialView() throws Exception {
        ExtensionRegistry registry = new ExtensionRegistry();
        // base 常驻且被 leaf 依赖；调整线程不断把 leaf 加入/下线，
        // 调用线程因此必须始终看到"leaf 在则 base 必在"的完整视图。
        RecordingExtension base = ext("base", 10);
        registry.register(base);

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers + 3);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        Set<Throwable> errors = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                try {
                    while (stop.getCount() > 0) {
                        List<ExtensionDescriptor> all = registry.getExtensions("p");
                        List<ExtensionDescriptor> enabled = registry.getEnabledExtensions("p");
                        List<ExtensionResult<String>> results =
                                ExtensionInvoker.invokeAll(registry, "p", "in");
                        List<String> descIds = all.stream().map(ExtensionDescriptor::id).toList();
                        List<String> callIds = results.stream()
                                .map(ExtensionResult::extensionId).toList();

                        // 不变量 1：视图内无重复 id，且只能是两种"完整装配"之一：
                        //   [base] 或 [base, leaf]；绝不允许 [leaf] 这种装到一半的悬空视图。
                        for (List<String> view : List.of(descIds, callIds)) {
                            if (new HashSet<>(view).size() != view.size()) {
                                throw new AssertionError("重复 id: " + view);
                            }
                            if (view.contains("leaf") && !view.contains("base")) {
                                throw new AssertionError("观察到缺少被依赖方的中间视图: " + view);
                            }
                            if (!view.equals(List.of("base"))
                                    && !view.equals(List.of("base", "leaf"))) {
                                throw new AssertionError("观察到非已提交装配的视图: " + view);
                            }
                        }
                        // 不变量 2：单次 invokeAll 内部状态自洽——leaf 出现时必有 base 的结果，
                        // 且 leaf 一定排在其依赖 base 之后（依赖优先）。
                        if (callIds.contains("leaf")
                                && callIds.indexOf("leaf") < callIds.indexOf("base")) {
                            throw new AssertionError("依赖顺序错误: " + callIds);
                        }
                        // 不变量 3：enabled 视图的 id 全部来自合法集合且无重复。
                        List<String> enabledIds = enabled.stream()
                                .map(ExtensionDescriptor::id).toList();
                        if (new HashSet<>(enabledIds).size() != enabledIds.size()
                                || !(enabledIds.equals(List.of())
                                        || enabledIds.equals(List.of("base"))
                                        || enabledIds.equals(List.of("leaf"))
                                        || enabledIds.equals(List.of("base", "leaf")))) {
                            throw new AssertionError("非法 enabled 视图: " + enabledIds);
                        }
                        checks.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        // 调整线程：循环 add(新实例) -> remove leaf，全部必须成功。
        pool.submit(() -> {
            try {
                while (stop.getCount() > 0) {
                    registry.add(ext("leaf", 20, "base"));
                    registry.remove("leaf");
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        // 开关线程：反复切换 base。
        pool.submit(() -> {
            try {
                while (stop.getCount() > 0) {
                    registry.setEnabled("base", false);
                    registry.setEnabled("base", true);
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        // 失败调整线程：反复尝试加入会初始化失败的扩展，每次都应被回滚、永不入视图。
        pool.submit(() -> {
            try {
                while (stop.getCount() > 0) {
                    try {
                        registry.add(failing("boom", 99));
                        errors.add(new AssertionError("失败新增竟然成功"));
                    } catch (DynamicReconfigurationException expected) {
                        // 预期回滚。
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        Thread.sleep(1000);
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("并发动态调整完成：一致性校验 {} 次，错误数 {}，base 初始化次数 {}（未受影响不重复初始化）",
                checks.get(), errors.size(), base.initCount());
        assertEquals(0, errors.size(), "并发期间不得出现中间视图: " + errors);
        assertEquals(1, base.initCount(), "未参与增删的 base 全程只初始化一次");
        assertSame(base, registry.getExtension("base"), "base 实例全程不变");
        assertTrue(registry.isEnabled("base"));
    }
}
