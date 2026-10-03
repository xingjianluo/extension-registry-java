package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 运行期动态新增扩展时，新扩展初始化失败，本次调整被整体回滚（旧视图继续可用）（骨架）。
 */
public class DynamicReconfigurationException extends ExtensionAssemblyException {

    private final String failedExtensionId;

    public DynamicReconfigurationException(String message, String failedExtensionId, Throwable cause) {
        super(message, cause);
        this.failedExtensionId = failedExtensionId;
    }

    public String failedExtensionId() {
        return failedExtensionId;
    }
}
