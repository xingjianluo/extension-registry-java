package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.List;

/**
 * 扩展仍被其它扩展依赖时拒绝下线。
 */
public class ExtensionInUseException extends ExtensionAssemblyException {

    private final String extensionId;
    private final List<String> dependents;

    public ExtensionInUseException(String message, String extensionId, List<String> dependents) {
        super(message);
        this.extensionId = extensionId;
        this.dependents = List.copyOf(dependents);
    }

    public String extensionId() {
        return extensionId;
    }

    public List<String> dependents() {
        return dependents;
    }
}
