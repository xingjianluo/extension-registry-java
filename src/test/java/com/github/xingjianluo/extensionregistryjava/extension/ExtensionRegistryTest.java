package com.github.xingjianluo.extensionregistryjava.extension;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistryTest.class);

    interface TestPoint extends ExtensionPoint {
        String id();
    }

    private static TestPoint stub(String id) {
        return () -> id;
    }

    // ------------------------------------------------------------------
    // 装配规则：失败原因可区分
    // ------------------------------------------------------------------

    @Test
    void duplicateIdIsRejectedWithDistinctReason() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(TestPoint.class, ExtensionDescriptor.of("a", 1), () -> stub("a"));
        registry.register(TestPoint.class, ExtensionDescriptor.of("a", 2), () -> stub("a"));

        ExtensionAssemblyException e = assertThrows(ExtensionAssemblyException.class,
                () -> registry.assemble(TestPoint.class));
        assertEquals(AssemblyFailureReason.DUPLICATE_ID, e.reason());
        log.info("duplicate id rejected, reason={}, message={}", e.reason(), e.getMessage());
    }

    @Test
    void danglingDependencyIsRejectedWithDistinctReason() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(TestPoint.class, ExtensionDescriptor.of("a", 1, "ghost"), () -> stub("a"));

        ExtensionAssemblyException e = assertThrows(ExtensionAssemblyException.class,
                () -> registry.assemble(TestPoint.class));
        assertEquals(AssemblyFailureReason.DANGLING_DEPENDENCY, e.reason());
        assertTrue(e.getMessage().contains("ghost"));
        log.info("dangling dependency rejected, reason={}, message={}", e.reason(), e.getMessage());
    }

    @Test
    void cyclicDependencyIsRejectedWithDistinctReason() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(TestPoint.class, ExtensionDescriptor.of("a", 1, "c"), () -> stub("a"));
        registry.register(TestPoint.class, ExtensionDescriptor.of("b", 2, "a"), () -> stub("b"));
        registry.register(TestPoint.class, ExtensionDescriptor.of("c", 3, "b"), () -> stub("c"));

        ExtensionAssemblyException e = assertThrows(ExtensionAssemblyException.class,
                () -> registry.assemble(TestPoint.class));
        assertEquals(AssemblyFailureReason.CYCLIC_DEPENDENCY, e.reason());
        log.info("cycle rejected, reason={}, message={}", e.reason(), e.getMessage());
    }

    // ------------------------------------------------------------------
    // 装配顺序：可复现、依赖优先于 order
    // ------------------------------------------------------------------

    @Test
    void assemblyOrderIsDeterministicAndReproducible() {
        List<String> expected = null;
        for (int round = 0; round < 5; round++) {
            ExtensionRegistry registry = new ExtensionRegistry();
            // 每轮以不同的声明顺序注册同一批扩展
            List<ExtensionDescriptor> descriptors = new ArrayList<>(List.of(
                    ExtensionDescriptor.of("alpha", 30),
                    ExtensionDescriptor.of("beta", 10, "gamma"), // 依赖优先于 order
                    ExtensionDescriptor.of("gamma", 20),
                    ExtensionDescriptor.of("delta", 10)));
            java.util.Collections.rotate(descriptors, round);
            for (ExtensionDescriptor d : descriptors) {
                registry.register(TestPoint.class, d, () -> stub(d.id()));
            }
            registry.assemble(TestPoint.class);
            List<String> order = registry.assembledIds(TestPoint.class);
            log.info("round {} assembly order: {} (rule: dependsOn before dependent, then order asc, then id asc)",
                    round, order);
            if (expected == null) {
                expected = order;
            }
            assertEquals(expected, order, "assembly order must be reproducible across rounds");
        }
        // delta(order=10) < gamma(order=20) < alpha(order=30)，beta 依赖 gamma 故在其后
        assertEquals(List.of("delta", "gamma", "beta", "alpha"), expected);
    }

    // ------------------------------------------------------------------
    // 故障隔离：单个扩展初始化失败不影响其它扩展
    // ------------------------------------------------------------------

    @Test
    void initializationFailureIsIsolated() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(TestPoint.class, ExtensionDescriptor.of("good-1", 1), () -> stub("good-1"));
        registry.register(TestPoint.class, ExtensionDescriptor.of("bad", 2), () -> {
            throw new IllegalStateException("boom");
        });
        registry.register(TestPoint.class, ExtensionDescriptor.of("good-2", 3), () -> stub("good-2"));

        ExtensionPointView<TestPoint> view = registry.assemble(TestPoint.class);

        assertEquals(List.of("good-1", "good-2"), view.active().stream().map(TestPoint::id).toList());
        assertEquals(1, view.failures().size());
        ExtensionFailure failure = view.failures().get(0);
        assertEquals("bad", failure.extensionId());
        assertEquals(AssemblyFailureReason.INITIALIZATION_FAILED, failure.reason());
        log.info("init failure isolated: failures={}, active={}",
                view.failures(), view.active().stream().map(TestPoint::id).toList());
    }

    // ------------------------------------------------------------------
    // 运行期启停：结果稳定、不重复初始化
    // ------------------------------------------------------------------

    @Test
    void disableYieldsStableResultAndToggleDoesNotReinitialize() {
        AtomicInteger initCount = new AtomicInteger();
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(TestPoint.class, ExtensionDescriptor.of("a", 1),
                () -> {
                    initCount.incrementAndGet();
                    return stub("a");
                });
        registry.register(TestPoint.class, ExtensionDescriptor.of("b", 2), () -> stub("b"));
        ExtensionPointView<TestPoint> view = registry.assemble(TestPoint.class);
        assertEquals(1, initCount.get());

        registry.setEnabled(TestPoint.class, "a", false);
        List<String> whileDisabled = view.invokeAll(TestPoint::id);
        assertEquals(List.of("b"), whileDisabled, "disabled extension must be skipped predictably");
        // 再次调用结果一致（稳定可预期）
        assertEquals(whileDisabled, view.invokeAll(TestPoint::id));

        // 反复切换不触发重复初始化
        for (int i = 0; i < 10; i++) {
            registry.setEnabled(TestPoint.class, "a", true);
            registry.setEnabled(TestPoint.class, "a", false);
        }
        assertEquals(1, initCount.get(), "toggling must not reinitialize the extension");

        registry.setEnabled(TestPoint.class, "a", true);
        assertEquals(List.of("a", "b"), view.invokeAll(TestPoint::id));
        log.info("toggle semantics verified: initCount={}, finalActive={}",
                initCount.get(), view.invokeAll(TestPoint::id));
    }

    @Test
    void togglingUnknownExtensionIsRejected() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(TestPoint.class, ExtensionDescriptor.of("a", 1), () -> stub("a"));
        registry.assemble(TestPoint.class);
        assertThrows(IllegalArgumentException.class,
                () -> registry.setEnabled(TestPoint.class, "nope", false));
    }

    // ------------------------------------------------------------------
    // 并发安全：并发注册与调用结果一致、无重复实例
    // ------------------------------------------------------------------

    @Test
    void concurrentRegistrationAndInvocationAreConsistent() throws InterruptedException {
        ExtensionRegistry registry = new ExtensionRegistry();
        int extensionCount = 32;
        Set<String> initializedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger totalInits = new AtomicInteger();

        // 并发注册
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < extensionCount; i++) {
            String id = "ext-" + i;
            pool.submit(() -> {
                await(start);
                registry.register(TestPoint.class, ExtensionDescriptor.of(id, 0), () -> {
                    initializedIds.add(id);
                    totalInits.incrementAndGet();
                    return stub(id);
                });
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        ExtensionPointView<TestPoint> view = registry.assemble(TestPoint.class);
        assertEquals(extensionCount, view.active().size());
        assertEquals(extensionCount, totalInits.get(), "each extension initialized exactly once");
        assertEquals(extensionCount, initializedIds.size(), "no duplicate instances");

        // 并发调用 + 并发启停：任何时刻观察到的结果都必须是装配顺序的一致子序列，
        // 无重复、无乱序、无装配到一半的状态
        List<String> expected = view.invokeAll(TestPoint::id);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            int idx = i;
            callers.submit(() -> {
                await(go);
                try {
                    for (int r = 0; r < 100; r++) {
                        if (idx % 2 == 0) {
                            List<String> observed = view.invokeAll(TestPoint::id);
                            assertEquals(observed.stream().distinct().toList(), observed,
                                    "no duplicate instances in one observation");
                            List<String> remaining = new ArrayList<>(expected);
                            for (String id : observed) {
                                assertTrue(remaining.remove(id),
                                        "observed ids must follow assembly order, got " + observed);
                            }
                        } else {
                            String id = "ext-" + (idx % extensionCount);
                            registry.setEnabled(TestPoint.class, id, false);
                            registry.setEnabled(TestPoint.class, id, true);
                        }
                    }
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                }
            });
        }
        go.countDown();
        callers.shutdown();
        assertTrue(callers.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "concurrent access errors: " + errors);
        assertEquals(expected, view.invokeAll(TestPoint::id),
                "after all toggles restored, result must equal the initial stable result");
        assertEquals(extensionCount, totalInits.get(), "toggling under concurrency must not reinitialize");
        log.info("concurrency verified: {} extensions, {} inits, stable order {}",
                extensionCount, totalInits.get(), expected);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
