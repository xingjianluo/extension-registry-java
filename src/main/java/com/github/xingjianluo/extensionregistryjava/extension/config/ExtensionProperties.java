package com.github.xingjianluo.extensionregistryjava.extension.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 扩展机制相关配置项。
 *
 * <p>示例：</p>
 * <pre>
 * extension:
 *   disabled:
 *     - "com.example.GreetingExtension#formal"   # 扩展点全限定类名#扩展标识
 * </pre>
 */
@ConfigurationProperties(prefix = "extension")
public class ExtensionProperties {

    /**
     * 启动时即处于禁用状态的扩展，格式为 {@code <扩展点全限定类名>#<扩展标识>}。
     * 禁用只影响运行期调用结果，不会跳过装配与校验。
     */
    private List<String> disabled = new ArrayList<>();

    public List<String> getDisabled() {
        return disabled;
    }

    public void setDisabled(List<String> disabled) {
        this.disabled = disabled == null ? new ArrayList<>() : disabled;
    }

    /** 解析一条禁用配置为 [pointId, extensionId]。 */
    public static String[] parseEntry(String entry) {
        int idx = entry == null ? -1 : entry.indexOf('#');
        if (idx <= 0 || idx == entry.length() - 1) {
            throw new IllegalArgumentException(
                    "invalid extension.disabled entry, expected '<pointClass>#<extensionId>': " + entry);
        }
        return new String[]{entry.substring(0, idx), entry.substring(idx + 1)};
    }
}
