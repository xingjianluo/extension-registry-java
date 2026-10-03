package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 对未注册（或已下线）的扩展 id 执行下线等操作。
 *
 * <p>继承 {@link IllegalArgumentException}，与既有 {@code setEnabled} 对未知 id 的
 * 拒绝类型保持兼容。
 */
public class ExtensionNotRegisteredException extends IllegalArgumentException {

    private final String extensionId;

    public ExtensionNotRegisteredException(String message, String extensionId) {
        super(message);
        this.extensionId = extensionId;
    }

    public String extensionId() {
        return extensionId;
    }
}
