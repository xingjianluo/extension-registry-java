package com.github.xingjianluo.extensionregistryjava.extension.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 扩展机制配置项。
 *
 * <p>配置前缀 {@code extension}，支持：
 * <pre>
 * extension.enabled.&lt;扩展id&gt;=true|false
 * </pre>
 * 未列出的扩展默认启用。初始化失败的扩展无论如何配置都会被强制禁用并隔离。
 */
@ConfigurationProperties(prefix = "extension")
public class ExtensionProperties {

    /**
     * key 为扩展 id，value 为期望的启用状态；缺省视为 true。
     */
    private final Map<String, Boolean> enabled = new LinkedHashMap<>();

    public Map<String, Boolean> getEnabled() {
        return enabled;
    }
}
