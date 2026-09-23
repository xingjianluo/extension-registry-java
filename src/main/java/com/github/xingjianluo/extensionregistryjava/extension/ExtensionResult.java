package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 单次扩展调用的结果（骨架）。
 */
public record ExtensionResult<O>(String extensionId, ExtensionState state, boolean enabled,
                                 O value, Throwable error) {

    public boolean succeeded() {
        return state == ExtensionState.INITIALIZED && enabled && error == null;
    }
}
