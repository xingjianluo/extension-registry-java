package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 同一次快照视图内解析出的"描述 + 实例"配对（骨架）。
 */
public record ResolvedExtension(ExtensionDescriptor descriptor, Extension<?, ?> extension) {
}
