package com.github.xingjianluo.extensionregistryjava.demo;

import com.github.xingjianluo.extensionregistryjava.extension.ExtensionPoint;

/**
 * 演示用扩展点：问候语生成。
 */
public interface GreetingExtension extends ExtensionPoint {

    /** 为指定名字生成一句问候语。 */
    String greet(String name);
}
