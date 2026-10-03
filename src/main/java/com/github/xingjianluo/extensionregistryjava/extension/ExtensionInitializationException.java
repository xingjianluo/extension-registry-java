package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 运行期原子装配批次中，新增扩展初始化失败导致整批回滚。
 */
public class ExtensionInitializationException extends ExtensionAssemblyException {

    private final String extensionId;

    public ExtensionInitializationException(String message, String extensionId, Throwable cause) {
        super(message, cause);
        this.extensionId = extensionId;
    }

    public String extensionId() {
        return extensionId;
    }
}
