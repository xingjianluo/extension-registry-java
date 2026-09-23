package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 某一扩展点在某一时刻的不可变装配视图。
 * 调用方通过视图访问扩展，保证不会观察到装配到一半的状态。
 *
 * @param <T> 扩展点类型
 */
public interface ExtensionPointView<T extends ExtensionPoint> {

    /** 扩展点标识（通常为扩展点接口的全限定名）。 */
    String pointId();

    /** 当前可用（已初始化且已启用）的扩展，按装配顺序排列。 */
    List<T> active();

    /** 全部已装配扩展（含被禁用的），按装配顺序排列；初始化失败者不包含实例。 */
    List<ResolvedExtension<T>> assembled();

    /** 初始化失败的扩展记录。 */
    List<ExtensionFailure> failures();

    /** 指定扩展当前是否启用。 */
    boolean isEnabled(String extensionId);

    /**
     * 按装配顺序对所有可用扩展执行调用并收集结果。
     * 被禁用或初始化失败的扩展被跳过，返回结果稳定可预期。
     */
    <R> List<R> invokeAll(Function<T, R> invocation);

    /**
     * 按装配顺序调用第一个可用扩展；无可用扩展时返回 {@link Optional#empty()}。
     */
    <R> Optional<R> invokeFirst(Function<T, R> invocation);
}
