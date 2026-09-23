package com.github.xingjianluo.extensionregistryjava.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 装配顺序与校验规则测试：声明顺序、依赖优先、重复标识/悬空依赖/依赖成环可区分拒绝。
 */
class AssemblyOrderingTest {

    private static final Logger log = LoggerFactory.getLogger(AssemblyOrderingTest.class);

    private static RecordingExtension ext(String id, int order, String... deps) {
        return new RecordingExtension(id, "point-a", order, Set.of(deps), false);
    }

    private static void logReport(String caseName, AssemblyReport report) {
        log.info("==== 用例[{}] 装配顺序与判定依据 ====", caseName);
        for (AssemblyRecord r : report.records()) {
            log.info("装配位[{}] id={} point={} declaredOrder={} dependencies={} state={} | 判定依据: {}",
                    r.index(), r.id(), r.point(), r.declaredOrder(), r.dependencies(), r.state(), r.reason());
        }
    }

    @Test
    void orderFollowsDeclaredOrderWhenNoDependencies() {
        ExtensionRegistry registry = new ExtensionRegistry();
        // 故意以乱序注册，证明结果不依赖注册顺序。
        registry.registerAll(List.of(
                ext("c", 30), ext("a", 10), ext("b", 20)));

        List<String> ids = registry.getExtensions("point-a").stream()
                .map(ExtensionDescriptor::id).toList();

        logReport("无依赖-按声明顺序", registry.getLastAssemblyReport());
        assertEquals(List.of("a", "b", "c"), ids);
    }

    @Test
    void tiesAreBrokenByIdDeterministically() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerAll(List.of(ext("zeta", 5), ext("alpha", 5)));

        List<String> ids = registry.getExtensions("point-a").stream()
                .map(ExtensionDescriptor::id).toList();

        logReport("order相同-按id字典序", registry.getLastAssemblyReport());
        assertEquals(List.of("alpha", "zeta"), ids);
    }

    @Test
    void dependenciesArePlacedBeforeDependents() {
        ExtensionRegistry registry = new ExtensionRegistry();
        // x 声明顺序最小但依赖 d，d 必须排在 x 前。
        registry.registerAll(List.of(
                ext("x", 1, "d"),
                ext("d", 100),
                ext("m", 50, "d")));

        List<String> ids = registry.getExtensions("point-a").stream()
                .map(ExtensionDescriptor::id).toList();

        logReport("依赖优先", registry.getLastAssemblyReport());
        // d 是共同依赖必须最先；x 与 m 之间无约束，按声明顺序 x(1) 在 m(50) 前。
        assertEquals(List.of("d", "x", "m"), ids);
    }

    @Test
    void duplicateIdIsRejectedAndRegistryStaysConsistent() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(ext("dup", 1));
        int before = registry.getExtensions("point-a").size();

        DuplicateExtensionException ex = assertThrows(DuplicateExtensionException.class,
                () -> registry.registerAll(List.of(ext("dup", 2), ext("other", 3))));
        log.info("重复标识被拒绝，原因: {}", ex.getMessage());

        // 失败批次不产生任何部分装配：仍只有最初的 1 个。
        assertEquals(before, registry.getExtensions("point-a").size());
        assertEquals(before, 1);
        assertInstanceOf(ExtensionAssemblyException.class, ex);
    }

    @Test
    void danglingDependencyIsRejectedWithDistinctReason() {
        ExtensionRegistry registry = new ExtensionRegistry();
        DanglingDependencyException ex = assertThrows(DanglingDependencyException.class,
                () -> registry.registerAll(List.of(ext("a", 1, "missing"))));
        log.info("悬空依赖被拒绝，原因: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("missing"));
        assertEquals(0, registry.getExtensions("point-a").size());
    }

    @Test
    void dependencyCycleIsRejectedWithCyclePath() {
        ExtensionRegistry registry = new ExtensionRegistry();
        DependencyCycleException ex = assertThrows(DependencyCycleException.class,
                () -> registry.registerAll(List.of(
                        ext("a", 1, "b"),
                        ext("b", 2, "c"),
                        ext("c", 3, "a"))));
        log.info("依赖成环被拒绝，原因: {}，环路径: {}", ex.getMessage(), ex.cycle());
        assertTrue(ex.getMessage().contains("环"));
        assertTrue(ex.cycle().size() >= 3);
        assertEquals(0, registry.getExtensions("point-a").size());
    }

    @Test
    void selfDependencyIsAlsoACycle() {
        ExtensionRegistry registry = new ExtensionRegistry();
        assertThrows(DependencyCycleException.class,
                () -> registry.registerAll(List.of(ext("self", 1, "self"))));
    }
}
