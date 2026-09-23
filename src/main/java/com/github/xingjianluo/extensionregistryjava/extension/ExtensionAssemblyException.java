package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 装配失败异常基类（骨架）。
 */
public class ExtensionAssemblyException extends RuntimeException {

    public ExtensionAssemblyException(String message) {
        super(message);
    }

    public ExtensionAssemblyException(String message, Throwable cause) {
        super(message, cause);
    }
}
