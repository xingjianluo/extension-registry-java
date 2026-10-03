package com.github.xingjianluo.extensionregistryjava.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
 * 运行期动态增删测试：
 * 新增参与装配/调用、下线彻底消失、依赖保护、整批回滚、未受影响扩展不重复初始化且保留开关、
 * 移除后再加按新实例语义、装配顺序确定稳定、并发下只能看到完整装配视图。
 */
class DynamicAdjustmentTest {

    private static final Logger log = LoggerFactory.getLogger(DynamicAdjustmentTest.class);

    private static RecordingExtension ext(String id, int order, String... deps) {
        return new RecordingExtension(id, "p", order, Set.of(deps), false);
    }

    private static void logReport(String caseName, ExtensionRegistry registry) {
        log.info("==== 用例[{}] 装配顺序与判定依据 ====", caseName);
        for (AssemblyRecord r : registry.getLastAssemblyReport().records()) {
            log.info("装配位[{}] id={} point={} declaredOrder={} dependencies={} state={} enabled={} | 判定依据: {}",
                    r.index(), r.id(), r.point(), r.declaredOrder(), r.dependencies(), r.state(),
                    registry.isEnabled(r.id()), r.reason());
        }
    }

    @Test
    void addedExtensionJoinsAssemblyBySameRulesAndIsInvoked() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension base = ext("base", 100);
        registry.register(base);

        // child 声明顺序更小但依赖 base，必须排在 base 之后；x 无依赖按声明顺序排在最前。
        RecordingExtension child = ext("child", 10, "base");
        RecordingExtension x = ext("x", 5);
        registry.addAll(List.of(child, x));

        List<String> ids = registry.getExtensions("p").stream()
                .map(ExtensionDescriptor::id).toList();
        logReport("动态新增-参与排序", registry);
        assertEquals(List.of("x", "base", "child"), ids);

        List<ExtensionResult<String>> results = ExtensionInvoker.invokeAll(registry, "p", "V");
        assertEquals(List.of("x:V", "base:V", "child:V"),
                results.stream().map(ExtensionResult::value).toList());
        assertEquals(1, child.initCount());
        assertEquals(1, x.initCount());
    }

    @Test
    void removedExtensionDisappearsCompletelyFromQueryAndInvocation() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension a = ext("a", 1);
        RecordingExtension b = ext("b", 2);
        RecordingExtension c = ext("c", 3);
        registry.registerAll(List.of(a, b, c));

        registry.unregister("b");
        logReport("动态下线-彻底消失", registry);

        assertEquals(List.of("a", "c"),
                registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertEquals(List.of("a", "c"),
                registry.getEnabledExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertNull(registry.getDescriptor("b"));
        assertNull(registry.<String, String>getExtension("b"));
        assertFalse(registry.isEnabled("b"));

        List<ExtensionResult<String>> results = ExtensionInvoker.invokeAll(registry, "p", "Q");
        assertEquals(2, results.size());
        assertEquals(List.of("a:Q", "c:Q"),
                results.stream().map(ExtensionResult::value).toList());
    }

    @Test
    void removeIsRejectedWhileDependentRemainsAndViewUnchanged() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerAll(List.of(ext("base", 1), ext("vip", 10, "base")));

        ExtensionInUseException ex = assertThrows(ExtensionInUseException.class,
                () -> registry.unregister("base"));
        log.info("依赖保护拒绝下线: {}, 依赖方={}", ex.getMessage(), ex.dependents());

        assertEquals("base", ex.extensionId());
        assertEquals(List.of("vip"), ex.dependents());
        // 被拒绝后不产生任何部分下线：base 仍在、依赖关系完好、可正常调用。
        assertEquals(List.of("base", "vip"),
                registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertEquals(2, ExtensionInvoker.invokeAll(registry, "p", "D").size());
    }

    @Test
    void batchUnregisterCanRemoveDependentAndDependencyTogether() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerAll(List.of(ext("base", 1), ext("vip", 10, "base"), ext("other", 20)));

        // 顺序无关：先列被依赖者也必须作为整批成功（vip 同批移除，不算仍在依赖）。
        registry.unregisterAll(List.of("base", "vip"));
        logReport("整批下线依赖簇", registry);

        assertEquals(List.of("other"),
                registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertNull(registry.getDescriptor("base"));
        assertNull(registry.getDescriptor("vip"));
    }

    @Test
    void unregisterUnknownOrDuplicateRequestIsRejectedAtomically() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension a = ext("a", 1);
        registry.register(a);

        ExtensionNotRegisteredException ex = assertThrows(ExtensionNotRegisteredException.class,
                () -> registry.unregister("ghost"));
        assertEquals("ghost", ex.extensionId());
        assertTrue(ex instanceof IllegalArgumentException);

        // 批内重复 id 拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> registry.unregisterAll(List.of("a", "a")));
        // 批次中含未知 id：整批拒绝，合法的 a 仍在。
        assertThrows(ExtensionNotRegisteredException.class,
                () -> registry.unregisterAll(List.of("a", "ghost")));
        assertEquals(1, registry.getExtensions("p").size());
    }

    @Test
    void unaffectedExtensionsAreNotReinitializedAndKeepEnabledState() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension keep = ext("keep", 1);
        RecordingExtension muted = ext("muted", 2);
        registry.registerAll(List.of(keep, muted));
        registry.setEnabled("muted", false);

        // 动态新增：未受影响扩展不重复初始化，muted 的禁用状态保留。
        RecordingExtension newcomer = ext("newcomer", 3);
        registry.add(newcomer);
        assertEquals(1, keep.initCount());
        assertEquals(1, muted.initCount());
        assertFalse(registry.isEnabled("muted"));
        assertEquals(0, muted.executeCount());

        // 再把新来的下线：老扩展状态依旧，装配顺序按统一规则重算。
        registry.unregister("newcomer");
        logReport("增删后未受影响扩展状态保留", registry);
        assertEquals(1, keep.initCount());
        assertFalse(registry.isEnabled("muted"));
        assertEquals(List.of("keep", "muted"),
                registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertTrue(registry.getDescriptor("muted").enabled() == false);
    }

    @Test
    void readdAfterRemoveUsesFreshInstanceAndReinitializes() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension first = ext("a", 1);
        registry.register(first);
        first.execute("warmup");
        registry.unregister("a");

        // 同一标识重新加回：必须提交新实例，注册表按全新扩展重新初始化，不沿用旧实例/状态。
        RecordingExtension second = ext("a", 1);
        registry.add(second);

        assertSame(second, registry.<String, String>getExtension("a"));
        assertNotSame(first, registry.<String, String>getExtension("a"));
        assertEquals(1, second.initCount(), "新实例必须重新初始化一次");
        assertEquals(0, second.executeCount(), "新实例不得继承旧实例的执行计数等残留状态");
        assertEquals(1, first.initCount(), "旧实例不再被触碰");
        assertTrue(registry.isEnabled("a"), "新实例启用状态为默认启用，不残留旧开关");
        logReport("移除后再加入-新实例语义", registry);
    }

    @Test
    void addWithInvalidGraphIsRejectedAndOldViewStaysUsable() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension base = ext("base", 1);
        registry.register(base);

        // 悬空依赖
        assertThrows(DanglingDependencyException.class,
                () -> registry.add(ext("dangling", 2, "missing")));
        // 成环（两个新扩展互依）
        assertThrows(DependencyCycleException.class,
                () -> registry.addAll(List.of(ext("c1", 2, "c2"), ext("c2", 3, "c1"))));
        // 与已注册重复
        assertThrows(DuplicateExtensionException.class,
                () -> registry.add(ext("base", 5)));

        logReport("非法新增全部拒绝-旧视图不变", registry);
        assertEquals(List.of("base"),
                registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertEquals(1, base.initCount());
        assertEquals(List.of("base:ok"),
                ExtensionInvoker.invokeEnabled(registry, "p", "ok"));
    }

    @Test
    void addBatchRollsBackWhenAnyNewExtensionFailsInit() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension base = ext("base", 1);
        registry.register(base);

        RecordingExtension good = ext("good", 2);
        RecordingExtension bad = new RecordingExtension("bad", "p", 3, Set.of(), true);
        ExtensionInitializationException ex = assertThrows(ExtensionInitializationException.class,
                () -> registry.addAll(List.of(good, bad)));
        log.info("初始化失败导致整批回滚: {}, 失败扩展={}", ex.getMessage(), ex.extensionId());
        assertEquals("bad", ex.extensionId());

        // 整批不生效：同批的 good 也不可见，调整前视图照常可用。
        assertEquals(List.of("base"),
                registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertNull(registry.getDescriptor("good"));
        assertNull(registry.getDescriptor("bad"));
        assertEquals(1, base.initCount());
        assertTrue(registry.isEnabled("base"));
        assertEquals(1, ExtensionInvoker.invokeAll(registry, "p", "R").size());

        // 回滚后修正问题重试可成功；之前被丢弃的 good 实例需重新提交。
        RecordingExtension goodRetry = ext("good", 2);
        registry.add(goodRetry);
        assertEquals(List.of("base", "good"),
                registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList());
        assertEquals(1, goodRetry.initCount());
        // 回滚批次中的旧 good 实例被丢弃：注册表里挂的是新实例，而非被丢弃的那个。
        assertSame(goodRetry, registry.<String, String>getExtension("good"));
        assertNotSame(good, registry.<String, String>getExtension("good"));
    }

    @Test
    void failedExtensionCanBeRemovedAndReaddedHealthyWithoutStateResidue() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension bad = new RecordingExtension("fx", "p", 1, Set.of(), true);
        RecordingExtension other = ext("other", 2);
        registry.registerAll(List.of(bad, other));
        assertEquals(ExtensionState.FAILED, registry.getDescriptor("fx").state());
        assertFalse(registry.isEnabled("fx"));

        // FAILED 扩展没有保留扩展依赖它，可以下线；下线后失败状态一并消失，other 不受影响。
        registry.unregister("fx");
        assertNull(registry.getDescriptor("fx"));
        assertEquals(ExtensionState.INITIALIZED, registry.getDescriptor("other").state());
        assertEquals(1, other.initCount());

        // 以同 id 的健康实例重新加回：新实例初始化成功、默认启用、不再继承 FAILED。
        RecordingExtension healthy = ext("fx", 1);
        registry.add(healthy);
        ExtensionDescriptor d = registry.getDescriptor("fx");
        assertEquals(ExtensionState.INITIALIZED, d.state());
        assertTrue(d.enabled());
        assertNull(d.failure());
        assertSame(healthy, registry.<String, String>getExtension("fx"));
        assertEquals(1, healthy.initCount());
        logReport("失败扩展下线后健康重加", registry);
    }

    @Test
    void orderingAfterRepeatedAdjustmentsIsDeterministicAcrossRuns() {
        List<List<String>> run1 = exerciseScenario();
        List<List<String>> run2 = exerciseScenario();
        log.info("连续两遍动态调整各阶段装配顺序: run1={} | run2={}", run1, run2);
        assertEquals(run1, run2, "同一套调整连跑两遍，每个阶段的装配顺序必须一致");
        // 关键阶段断言：无依赖按声明顺序；依赖优先；下线后仍稳定。
        assertEquals(List.of("a", "b", "c"), run1.get(0));
        assertEquals(List.of("a", "mid", "b", "c"), run1.get(1));
        assertEquals(List.of("a", "b", "c"), run1.get(2));
        // 新增 d 依赖 a，即便 order 最小也必须排在 a 之后，且与 b/c 无约束按 order 排前。
        assertEquals(List.of("a", "d", "b", "c"), run1.get(3));
    }

    private List<List<String>> exerciseScenario() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerAll(List.of(ext("c", 30), ext("a", 10), ext("b", 20)));
        List<List<String>> stages = new ArrayList<>();
        stages.add(ids(registry));
        registry.add(ext("mid", 15));
        stages.add(ids(registry));
        registry.unregister("mid");
        stages.add(ids(registry));
        registry.add(ext("d", 5, "a"));
        stages.add(ids(registry));
        registry.unregister("d");
        stages.add(ids(registry));
        return stages;
    }

    private static List<String> ids(ExtensionRegistry registry) {
        return registry.getExtensions("p").stream().map(ExtensionDescriptor::id).toList();
    }

    @Test
    void concurrentReadersOnlySeeCompleteAssemblyViews() throws Exception {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(ext("base", 1));

        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 2);
        CountDownLatch stop = new CountDownLatch(1);
        Set<Throwable> errors = ConcurrentHashMap.newKeySet();
        AtomicInteger iterations = new AtomicInteger();
        Set<List<String>> observedViews = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    while (stop.getCount() > 0) {
                        List<ExtensionResult<String>> results =
                                ExtensionInvoker.invokeAll(registry, "p", "in");
                        List<String> ids = results.stream()
                                .map(ExtensionResult::extensionId).toList();
                        // 只能看到两种完整视图之一：[base] 或 [base, child]。
                        // child 依赖 base，绝不允许出现 child 已可见而 base 缺失/未就绪等中间态。
                        boolean coherent = ids.equals(List.of("base"))
                                || ids.equals(List.of("base", "child"));
                        if (!coherent || new HashSet<>(ids).size() != ids.size()) {
                            throw new AssertionError("观察到装配中间视图: " + ids);
                        }
                        if (results.stream().anyMatch(r -> r.enabled() && r.value() == null)) {
                            throw new AssertionError("已就绪扩展缺少实例/结果: " + results);
                        }
                        observedViews.add(ids);
                        iterations.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        // 调整线程：反复新增/下线 child（依赖 base），期间穿插开关切换。
        // 两个调整线程并发对同一 id 增删时，重复新增（Duplicate）/重复下线（未注册）属于
        // 正确的串行化竞争拒绝，不是错误，吞掉后继续；任何其它异常都算失败。
        Runnable adjuster = () -> {
            try {
                while (stop.getCount() > 0) {
                    try {
                        registry.add(ext("child", 5, "base"));
                    } catch (DuplicateExtensionException expected) {
                        // 另一个线程已添加
                    }
                    try {
                        registry.setEnabled("child", false);
                        registry.setEnabled("child", true);
                    } catch (IllegalArgumentException transientlyAbsent) {
                        // 已被另一个线程下线：setEnabled 对未注册 id 的既有拒绝类型
                    }
                    try {
                        registry.unregister("child");
                    } catch (ExtensionNotRegisteredException expected) {
                        // 已被另一个线程下线
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        };
        pool.submit(adjuster);
        pool.submit(adjuster);

        Thread.sleep(1000);
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("并发动态调整：调用迭代 {} 次，错误 {}，观察到的完整视图 {}",
                iterations.get(), errors.size(), observedViews);
        assertEquals(0, errors.size(), "并发下不得出现中间视图或异常: " + errors);
        // base 始终在且只初始化一次；child 反复增删期间，任一存活实例也只初始化一次。
        assertEquals(1, ((RecordingExtension) registry.<String, String>getExtension("base")).initCount());
        assertTrue(observedViews.contains(List.of("base")));
        assertTrue(observedViews.contains(List.of("base", "child")),
                "并发期间两种完整视图都应被观察到");
    }
}
