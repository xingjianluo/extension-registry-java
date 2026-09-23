package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 悬空依赖（骨架）。
 */
public class DanglingDependencyException extends ExtensionAssemblyException {

    public DanglingDependencyException(String message) {
        super(message);
    }
}
