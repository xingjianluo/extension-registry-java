package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 装配失败原因，各取值可相互区分。
 */
public enum AssemblyFailureReason {
    /** 同一扩展点下出现重复的扩展标识。 */
    DUPLICATE_ID,
    /** 声明的依赖指向不存在的扩展标识。 */
    DANGLING_DEPENDENCY,
    /** 依赖关系构成环。 */
    CYCLIC_DEPENDENCY,
    /** 扩展实例初始化（工厂方法）抛出异常。 */
    INITIALIZATION_FAILED
}
