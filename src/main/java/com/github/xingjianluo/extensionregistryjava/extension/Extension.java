package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.Set;

/**
 * 扩展点实现契约（骨架）。
 */
public interface Extension<I, O> {

    String id();

    String point();

    int order();

    Set<String> dependencies();

    void initialize() throws Exception;

    O execute(I input);
}
