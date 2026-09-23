package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 扩展点契约的标记接口。
 *
 * <p>一个扩展点即一个继承本接口的 Java 接口；同一扩展点下可注册多个实现，
 * 由 {@link ExtensionRegistry} 按声明的顺序与依赖关系装配。</p>
 */
public interface ExtensionPoint {
}
