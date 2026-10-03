package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下线扩展时，仍有其它现存扩展依赖该扩展，下线请求被整体拒绝（骨架）。
 */
public class ExtensionInUseException extends ExtensionAssemblyException {

    private final transient Map<String, List<String>> blockedBy;

    public ExtensionInUseException(String message, Map<String, List<String>> blockedBy) {
        super(message);
        this.blockedBy = Collections.unmodifiableMap(new LinkedHashMap<>(blockedBy));
    }

    /**
     * key = 被请求下线但仍被依赖的扩展 id；value = 仍依赖它的现存扩展 id 列表。
     */
    public Map<String, List<String>> blockedBy() {
        return blockedBy;
    }
}
