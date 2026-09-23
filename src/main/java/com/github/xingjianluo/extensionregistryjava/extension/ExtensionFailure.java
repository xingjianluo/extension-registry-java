package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 单个扩展初始化失败的记录。初始化失败不会阻断其它扩展，
 * 而是以本记录的形式暴露在装配结果中。
 *
 * @param extensionId 失败的扩展标识
 * @param cause       初始化抛出的异常
 */
public record ExtensionFailure(String extensionId, Throwable cause) {

    public AssemblyFailureReason reason() {
        return AssemblyFailureReason.INITIALIZATION_FAILED;
    }
}
