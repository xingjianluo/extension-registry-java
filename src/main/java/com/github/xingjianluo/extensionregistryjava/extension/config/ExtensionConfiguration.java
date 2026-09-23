package com.github.xingjianluo.extensionregistryjava.extension.config;

import com.github.xingjianluo.extensionregistryjava.extension.ExtensionPoint;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扩展机制的 Spring 装配入口：
 * 收集所有 {@link ExtensionContribution} Bean，按扩展点分组注册并装配，
 * 最后应用配置中的禁用列表。
 */
@Configuration
@EnableConfigurationProperties(ExtensionProperties.class)
public class ExtensionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExtensionConfiguration.class);

    @Bean
    public ExtensionRegistry extensionRegistry() {
        return new ExtensionRegistry();
    }

    @Bean
    public SmartInitializingSingleton extensionAssembler(
            ExtensionRegistry registry,
            ObjectProvider<ExtensionContribution<?>> contributions,
            ExtensionProperties properties) {
        return () -> {
            Map<Class<? extends ExtensionPoint>, List<ExtensionContribution<?>>> byPoint = new LinkedHashMap<>();
            for (ExtensionContribution<?> contribution : contributions) {
                byPoint.computeIfAbsent(contribution.pointType(), k -> new java.util.ArrayList<>())
                        .add(contribution);
            }
            byPoint.forEach((pointType, list) -> {
                for (ExtensionContribution<?> c : list) {
                    registerUnchecked(registry, c);
                }
                registry.assemble(pointType);
            });
            applyDisabled(registry, properties);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerUnchecked(ExtensionRegistry registry, ExtensionContribution<?> c) {
        registry.register((Class) c.pointType(), c.descriptor(), c.factory());
    }

    private static void applyDisabled(ExtensionRegistry registry, ExtensionProperties properties) {
        for (String entry : properties.getDisabled()) {
            String[] parts = ExtensionProperties.parseEntry(entry);
            String pointId = parts[0];
            String extensionId = parts[1];
            try {
                @SuppressWarnings("unchecked")
                Class<? extends ExtensionPoint> pointType =
                        (Class<? extends ExtensionPoint>) Class.forName(pointId);
                registry.setEnabled(pointType, extensionId, false);
            } catch (ClassNotFoundException e) {
                log.warn("extension.disabled entry ignored, unknown point type: {}", entry);
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.warn("extension.disabled entry ignored: {} ({})", entry, e.getMessage());
            }
        }
    }
}
