package com.github.xingjianluo.extensionregistryjava.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.extensionregistryjava.extension.Extension;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionDescriptor;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionInUseException;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionInvoker;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionRegistry;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionResult;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionState;
import com.github.xingjianluo.extensionregistryjava.extension.RecordingExtension;
import com.github.xingjianluo.extensionregistryjava.extension.spring.ExtensionsAutoConfiguration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 集成测试：Bean 收集、配置驱动的启用/禁用、初始化失败隔离、重复 id 启动失败。
 */
class SpringIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SpringIntegrationTest.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(ExtensionsAutoConfiguration.class,
                    TestBeanConfig.class));

    @Configuration
    static class TestBeanConfig {
        @Bean
        Extension<String, String> alpha() {
            return new RecordingExtension("alpha", "calc", 20, Set.of(), false);
        }

        @Bean
        Extension<String, String> beta() {
            // 依赖 alpha，即便注册顺序在后也必须排在 alpha 之后调用
            return new RecordingExtension("beta", "calc", 10, Set.of("alpha"), false);
        }

        @Bean
        Extension<String, String> broken() {
            return new RecordingExtension("broken", "calc", 5, Set.of(), true);
        }
    }

    @Test
    void beansAreAssembledOrderedConfiguredToggleAppliedAndFailureIsolated() {
        runner.withPropertyValues("extension.enabled.beta=false").run(context -> {
            assertTrue(context.isRunning());
            ExtensionRegistry registry = context.getBean(ExtensionRegistry.class);

            List<ExtensionDescriptor> all = registry.getExtensions("calc");
            log.info("Spring 装配顺序: {}", all.stream()
                    .map(d -> d.id() + "(state=" + d.state() + ",enabled=" + d.enabled() + ")")
                    .toList());

            // 装配顺序：beta 依赖 alpha -> alpha 先；broken 声明顺序最小排第一。
            assertEquals(List.of("broken", "alpha", "beta"),
                    all.stream().map(ExtensionDescriptor::id).toList());

            // 初始化失败的 broken 被隔离。
            assertEquals(ExtensionState.FAILED, registry.getDescriptor("broken").state());
            assertFalse(registry.isEnabled("broken"));

            // 配置禁用 beta。
            assertFalse(registry.isEnabled("beta"));
            assertTrue(registry.isEnabled("alpha"));

            List<ExtensionResult<String>> results =
                    ExtensionInvoker.invokeAll(registry, "calc", "1");
            assertEquals(3, results.size());
            assertFalse(results.get(2).enabled()); // beta 被禁用
            assertEquals("alpha:1", results.get(1).value());
        });
    }

    @Test
    void unspecifiedExtensionsDefaultToEnabled() {
        runner.run(context -> {
            ExtensionRegistry registry = context.getBean(ExtensionRegistry.class);
            assertTrue(registry.isEnabled("alpha"));
            assertTrue(registry.isEnabled("beta"));
        });
    }

    @Test
    void duplicateBeanIdsFailStartupWithDistinctException() {
        new ApplicationContextRunner()
                .withConfiguration(UserConfigurations.of(ExtensionsAutoConfiguration.class,
                        DuplicateBeanConfig.class))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    log.info("重复 Bean 标识导致启动失败，原因: {}", failure.getMessage());
                    assertTrue(rootMessageContains(failure, "扩展标识重复"));
                });
    }

    @Configuration
    static class DuplicateBeanConfig {
        @Bean
        Extension<String, String> first() {
            return new RecordingExtension("same-id", "p", 1, Set.of(), false);
        }

        @Bean
        Extension<String, String> second() {
            return new RecordingExtension("same-id", "p", 2, Set.of(), false);
        }
    }

    @Test
    void runtimeAddAndRemoveWorksOnSpringManagedRegistryAndStaysCompatible() {
        runner.run(context -> {
            ExtensionRegistry registry = context.getBean(ExtensionRegistry.class);
            // 启动期静态装配保持原样。
            assertEquals(List.of("broken", "alpha", "beta"),
                    registry.getExtensions("calc").stream().map(ExtensionDescriptor::id).toList());

            // 运行期新增：依赖 alpha 的 gamma 按同一套规则排在 alpha 之后；
            // gamma(order15) 与 beta(order10) 无相互约束，按声明顺序 beta 仍在 gamma 前。
            RecordingExtension gamma = new RecordingExtension("gamma", "calc", 15, Set.of("alpha"), false);
            registry.add(gamma);
            assertEquals(List.of("broken", "alpha", "beta", "gamma"),
                    registry.getExtensions("calc").stream().map(ExtensionDescriptor::id).toList());
            List<ExtensionResult<String>> results = ExtensionInvoker.invokeAll(registry, "calc", "7");
            assertEquals(List.of("alpha:7", "beta:7", "gamma:7"),
                    results.stream().filter(ExtensionResult::succeeded)
                            .map(ExtensionResult::value).toList());

            // 依赖保护：alpha 被 beta/gamma 依赖，不允许下线。
            assertThrows(ExtensionInUseException.class, () -> registry.unregister("alpha"));

            // 下线 gamma 后，其从查询与调用中彻底消失，静态 Bean 不受影响、不重复初始化。
            registry.unregister("gamma");
            assertNull(registry.getDescriptor("gamma"));
            assertEquals(List.of("broken", "alpha", "beta"),
                    registry.getExtensions("calc").stream().map(ExtensionDescriptor::id).toList());
            assertEquals(1, gamma.initCount());
            assertFalse(registry.isEnabled("gamma"));
            log.info("Spring 环境下运行期增删完成，恢复静态装配视图");
        });
    }

    private static boolean rootMessageContains(Throwable t, String fragment) {
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains(fragment)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
