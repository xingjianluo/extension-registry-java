package com.github.xingjianluo.extensionregistryjava.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 初始化故障隔离、运行期启用/禁用、调用稳定性与一次性初始化测试。
 */
class LifecycleAndIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleAndIsolationTest.class);

    private static RecordingExtension ok(String id, int order, String... deps) {
        return new RecordingExtension(id, "p", order, Set.of(deps), false);
    }

    private static void logReport(ExtensionRegistry registry) {
        log.info("==== 装配/生命周期报告 ====");
        for (AssemblyRecord r : registry.getLastAssemblyReport().records()) {
            log.info("装配位[{}] id={} declaredOrder={} state={} enabled={} | {}",
                    r.index(), r.id(), r.declaredOrder(), r.state(),
                    registry.isEnabled(r.id()), r.reason());
        }
    }

    @Test
    void failedInitializationIsIsolatedAndOthersRemainUsable() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension bad = new RecordingExtension("bad", "p", 1, Set.of(), true);
        RecordingExtension good = ok("good", 2);
        registry.registerAll(List.of(bad, good));

        logReport(registry);

        ExtensionDescriptor badDesc = registry.getDescriptor("bad");
        ExtensionDescriptor goodDesc = registry.getDescriptor("good");
        assertEquals(ExtensionState.FAILED, badDesc.state());
        assertNotNull(badDesc.failure());
        assertEquals(ExtensionState.INITIALIZED, goodDesc.state());
        assertTrue(goodDesc.enabled());

        // 故障扩展被强制禁用，且不允许重新启用。
        assertFalse(registry.isEnabled("bad"));
        assertThrows(IllegalStateException.class, () -> registry.setEnabled("bad", true));

        // 故障扩展不出现在可调用集合里，其它扩展照常。
        List<String> enabledIds = registry.getEnabledExtensions("p").stream()
                .map(ExtensionDescriptor::id).toList();
        assertEquals(List.of("good"), enabledIds);
    }

    @Test
    void disabledExtensionReturnsStableResultAndIsNotExecuted() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension a = ok("a", 1);
        RecordingExtension b = ok("b", 2);
        registry.registerAll(List.of(a, b));

        registry.setEnabled("b", false);
        List<ExtensionResult<String>> results1 = ExtensionInvoker.invokeAll(registry, "p", "X");
        List<ExtensionResult<String>> results2 = ExtensionInvoker.invokeAll(registry, "p", "X");

        log.info("禁用 b 后两次调用结果: {} | {}", results1, results2);

        assertEquals(2, results1.size());
        ExtensionResult<String> rB = results1.get(1);
        assertFalse(rB.enabled());
        assertEquals(null, rB.value());
        assertEquals(null, rB.error());
        assertEquals(0, b.executeCount());
        // 稳定可预期：两次调用完全一致。
        assertEquals(results1, results2);
        // 启用的 a 正常执行一次/调用。
        assertEquals("a:X", results1.get(0).value());
        assertEquals(2, a.executeCount());
    }

    @Test
    void repeatedTogglingDoesNotReinitializeOrLeakState() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension a = ok("a", 1);
        registry.register(a);

        for (int i = 0; i < 5; i++) {
            registry.setEnabled("a", false);
            registry.setEnabled("a", true);
        }
        registry.setEnabled("a", true); // 重复设为相同值，应无副作用

        logReport(registry);

        // 只初始化一次。
        assertEquals(1, a.initCount());
        assertEquals(ExtensionState.INITIALIZED, registry.getDescriptor("a").state());
        List<ExtensionResult<String>> results = ExtensionInvoker.invokeAll(registry, "p", "Y");
        assertEquals("a:Y", results.get(0).value());
        assertEquals(1, a.executeCount());
    }

    @Test
    void invokeAllReportsFailedExtensionAndInvokeEnabledSkipsIt() {
        ExtensionRegistry registry = new ExtensionRegistry();
        RecordingExtension bad = new RecordingExtension("bad", "p", 1, Set.of(), true);
        RecordingExtension good = ok("good", 2);
        registry.registerAll(List.of(bad, good));

        List<ExtensionResult<String>> all = ExtensionInvoker.invokeAll(registry, "p", "Z");
        log.info("invokeAll 结果: {}", all);
        assertEquals(ExtensionState.FAILED, all.get(0).state());
        assertFalse(all.get(0).enabled());

        List<String> values = ExtensionInvoker.invokeEnabled(registry, "p", "Z");
        assertEquals(List.of("good:Z"), values);
    }

    @Test
    void unknownIdToggleIsRejected() {
        ExtensionRegistry registry = new ExtensionRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.setEnabled("ghost", false));
    }
}
