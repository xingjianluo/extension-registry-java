package com.github.xingjianluo.extensionregistryjava.extension.config;

import com.github.xingjianluo.extensionregistryjava.extension.ExtensionDescriptor;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionPoint;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 扩展贡献声明：以 Spring Bean 的形式向注册表贡献一个扩展实现。
 *
 * <p>用法：在 {@code @Configuration} 中声明
 * {@code @Bean ExtensionContribution<MyPoint>}，框架会在上下文刷新后
 * 统一收集、装配。</p>
 *
 * @param pointType  扩展点接口类型
 * @param descriptor 扩展描述（标识、顺序、依赖）
 * @param factory    实例工厂，装配期惰性调用且仅调用一次
 * @param <T>        扩展点类型
 */
public record ExtensionContribution<T extends ExtensionPoint>(Class<T> pointType,
                                                              ExtensionDescriptor descriptor,
                                                              Supplier<? extends T> factory) {

    public ExtensionContribution {
        Objects.requireNonNull(pointType, "pointType");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(factory, "factory");
    }

    public static <T extends ExtensionPoint> ExtensionContribution<T> of(
            Class<T> pointType, String id, int order, Supplier<? extends T> factory, String... dependsOn) {
        return new ExtensionContribution<>(pointType, ExtensionDescriptor.of(id, order, dependsOn), factory);
    }
}
