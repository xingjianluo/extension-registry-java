package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 装配期失败：重复标识、悬空依赖、依赖成环。
 * 通过 {@link #reason()} 可区分失败原因。
 */
public class ExtensionAssemblyException extends RuntimeException {

    private final AssemblyFailureReason reason;

    public ExtensionAssemblyException(AssemblyFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AssemblyFailureReason reason() {
        return reason;
    }
}
