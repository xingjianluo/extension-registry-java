package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 装配完成的单个扩展：描述信息 + 已初始化的实例。
 *
 * @param descriptor 扩展描述
 * @param instance   初始化后的扩展实例
 * @param <T>        扩展点类型
 */
public record ResolvedExtension<T extends ExtensionPoint>(ExtensionDescriptor descriptor, T instance) {

    public String id() {
        return descriptor.id();
    }
}
