package com.github.xingjianluo.extensionregistryjava.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 并发注册、并发调用与并发开关测试：
 * 不出现重复实例、查询结果始终一致、每个扩展仅初始化一次。
 */
class ConcurrentAccessTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentAccessTest.class);

    private static final int REGISTER_THREADS = 40;
    private static final int CALLER_THREADS = 8;

    @Test
    void concurrentRegistrationProducesExactlyOneInstancePerId() throws Exception {
        ExtensionRegistry registry = new ExtensionRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(REGISTER_THREADS);
        CyclicBarrier barrier = new CyclicBarrier(REGISTER_THREADS);
        CountDownLatch done = new CountDownLatch(REGISTER_THREADS);
        Set<Throwable> errors = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < REGISTER_THREADS; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    barrier.await();
                    registry.register(new RecordingExtension(
                            "ext-" + idx, "p", idx, Set.of(), false));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        List<ExtensionDescriptor> all = registry.getExtensions("p");
        log.info("并发注册完成：共观察到 {} 个扩展（期望 {}），装配顺序前10: {}",
                all.size(), REGISTER_THREADS,
                all.stream().limit(10).map(ExtensionDescriptor::id).toList());

        assertEquals(0, errors.size(), "并发注册不应抛错: " + errors);
        assertEquals(REGISTER_THREADS, all.size());
        assertEquals(REGISTER_THREADS, new HashSet<>(
                all.stream().map(ExtensionDescriptor::id).toList()).size());

        // 每个扩展都恰好初始化一次，不重复实例化。
        for (ExtensionDescriptor d : all) {
            RecordingExtension ext = (RecordingExtension) registry.<String, String>getExtension(d.id());
            assertEquals(1, ext.initCount(), "扩展 " + d.id() + " 必须且仅初始化一次");
        }

        // 装配顺序只由 declaredOrder 决定，可复现。
        List<String> ids = all.stream().map(ExtensionDescriptor::id).toList();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < REGISTER_THREADS; i++) {
            expected.add("ext-" + i);
        }
        assertEquals(expected, ids);
    }

    @Test
    void concurrentCallsAndTogglesSeeConsistentSnapshots() throws Exception {
        ExtensionRegistry registry = new ExtensionRegistry();
        List<RecordingExtension> extensions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            extensions.add(new RecordingExtension("e" + i, "p", i, Set.of(), false));
        }
        registry.registerAll(extensions);

        ExecutorService pool = Executors.newFixedThreadPool(CALLER_THREADS + 2);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger iterations = new AtomicInteger();
        Set<Throwable> errors = ConcurrentHashMap.newKeySet();

        // 调用线程：每次看到的结果数量必须恒定，且不得出现重复 id。
        for (int c = 0; c < CALLER_THREADS; c++) {
            pool.submit(() -> {
                try {
                    while (stop.getCount() > 0) {
                        List<ExtensionResult<String>> results =
                                ExtensionInvoker.invokeAll(registry, "p", "in");
                        List<String> ids = results.stream()
                                .map(ExtensionResult::extensionId).toList();
                        if (ids.size() != 20 || new HashSet<>(ids).size() != ids.size()) {
                            throw new AssertionError("观察到不一致快照: " + ids);
                        }
                        iterations.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        // 开关线程：反复切换固定扩展。
        Runnable toggler = () -> {
            try {
                while (stop.getCount() > 0) {
                    registry.setEnabled("e7", false);
                    registry.setEnabled("e7", true);
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        };
        pool.submit(toggler);
        pool.submit(toggler);

        Thread.sleep(1000);
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("并发调用完成：成功迭代 {} 次，错误数 {}，e7 初始化次数 {}",
                iterations.get(), errors.size(), extensions.get(7).initCount());

        assertEquals(0, errors.size(), "并发调用/切换不应出现不一致: " + errors);
        // 无论怎么切换，初始化只发生一次。
        assertEquals(1, extensions.get(7).initCount());
        assertTrue(registry.isEnabled("e7"));
    }
}
