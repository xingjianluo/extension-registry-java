package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 重复扩展标识（骨架）。
 */
public class DuplicateExtensionException extends ExtensionAssemblyException {

    public DuplicateExtensionException(String message) {
        super(message);
    }
}
