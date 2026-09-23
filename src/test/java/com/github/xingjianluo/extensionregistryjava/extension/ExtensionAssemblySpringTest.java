package com.github.xingjianluo.extensionregistryjava.extension;

import com.github.xingjianluo.extensionregistryjava.demo.GreetingExtension;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 集成测试：贡献收集、装配顺序、配置驱动禁用。
 */
@SpringBootTest(properties = {
        "extension.disabled=com.github.xingjianluo.extensionregistryjava.demo.GreetingExtension#cheerful"
})
class ExtensionAssemblySpringTest {

    private static final Logger log = LoggerFactory.getLogger(ExtensionAssemblySpringTest.class);

    @Autowired
    private ExtensionRegistry registry;

    @Test
    void contributionsAreAssembledInDeterministicOrder() {
        ExtensionPointView<GreetingExtension> view = registry.view(GreetingExtension.class);
        List<String> assembled = view.assembled().stream().map(ResolvedExtension::id).toList();
        // casual 依赖 formal，故 formal 必在 casual 前；cheerful 无依赖按 order 排最后
        assertEquals(List.of("formal", "casual", "cheerful"), assembled);
        log.info("spring assembly order: {} (rule: dependsOn before dependent, then order asc)", assembled);
    }

    @Test
    void configuredDisabledExtensionIsExcludedFromInvocation() {
        ExtensionPointView<GreetingExtension> view = registry.view(GreetingExtension.class);
        assertFalse(view.isEnabled("cheerful"));
        assertTrue(view.isEnabled("formal"));
        List<String> greetings = view.invokeAll(g -> g.greet("tester"));
        assertEquals(2, greetings.size(), "disabled extension must be skipped");
        log.info("disabled via config: active greetings = {}", greetings);

        // 运行期重新启用后立即生效，且结果稳定
        registry.setEnabled(GreetingExtension.class, "cheerful", true);
        assertEquals(3, view.invokeAll(g -> g.greet("tester")).size());
        registry.setEnabled(GreetingExtension.class, "cheerful", false);
        assertEquals(greetings, view.invokeAll(g -> g.greet("tester")));
    }
}
