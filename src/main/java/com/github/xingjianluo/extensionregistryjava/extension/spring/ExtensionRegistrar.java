package com.github.xingjianluo.extensionregistryjava.extension.spring;

import com.github.xingjianluo.extensionregistryjava.extension.AssemblyReport;
import com.github.xingjianluo.extensionregistryjava.extension.Extension;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;
import java.util.Map;

/**
 * 收集 Spring 容器中所有 {@link Extension} Bean，按 Bean 名称排序后一次性注册到
 * {@link ExtensionRegistry}，使装配结果不依赖 Bean 发现顺序；随后应用
 * {@code extension.enabled.*} 配置确定初始启用状态。
 *
 * <p>启动期如果出现重复标识、悬空依赖或依赖成环，异常会向上传播导致应用上下文启动失败，
 * 并携带可区分的失败原因；初始化失败的单个扩展被隔离，不影响其它扩展与应用启动。
 */
public class ExtensionRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistrar.class);

    private final ExtensionRegistry registry;
    private final List<Extension<?, ?>> extensions;
    private final ExtensionProperties properties;

    public ExtensionRegistrar(ExtensionRegistry registry,
                              List<Extension<?, ?>> extensions,
                              ExtensionProperties properties) {
        this.registry = registry;
        this.extensions = extensions;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        // 不依赖 Spring 注入顺序：按 id 排序后再注册，拓扑排序内部再依据 order/依赖确定次序。
        List<Extension<?, ?>> sorted = extensions.stream()
                .sorted(java.util.Comparator.comparing(Extension::id))
                .toList();
        log.info("开始装配扩展 Bean，候选数量={}，候选 id 顺序={}",
                sorted.size(), sorted.stream().map(Extension::id).toList());

        registry.registerAll(sorted);

        Map<String, Boolean> configured = properties.getEnabled();
        for (Map.Entry<String, Boolean> entry : configured.entrySet()) {
            String id = entry.getKey();
            boolean expected = Boolean.TRUE.equals(entry.getValue());
            if (registry.getDescriptor(id) == null) {
                // 配置指向不存在的扩展时给出明确告警，避免静默忽略。
                log.warn("配置项 extension.enabled.{} 对应的扩展不存在，已忽略", id);
                continue;
            }
            try {
                registry.setEnabled(id, expected);
            } catch (IllegalStateException e) {
                // 初始化失败扩展无法启用，记录明确原因但不中断其它开关应用。
                log.warn("无法按配置启用扩展 '{}': {}", id, e.getMessage());
            }
        }

        AssemblyReport report = registry.getLastAssemblyReport();
        log.info("扩展装配报告（顺序与判定依据）:\n{}", report);
    }
}
