package com.github.xingjianluo.extensionregistryjava.demo;

import com.github.xingjianluo.extensionregistryjava.extension.config.ExtensionContribution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 演示扩展实现：三个实现按声明的顺序与依赖关系装配。
 * casual 依赖 formal，因此无论声明先后，formal 总是先装配。
 */
@Configuration
public class GreetingExtensions {

    @Bean
    public ExtensionContribution<GreetingExtension> casualGreeting() {
        return ExtensionContribution.of(GreetingExtension.class, "casual", 20,
                () -> name -> "Hi " + name + "!", "formal");
    }

    @Bean
    public ExtensionContribution<GreetingExtension> formalGreeting() {
        return ExtensionContribution.of(GreetingExtension.class, "formal", 10,
                () -> name -> "Good day, " + name + ".");
    }

    @Bean
    public ExtensionContribution<GreetingExtension> cheerfulGreeting() {
        return ExtensionContribution.of(GreetingExtension.class, "cheerful", 30,
                () -> name -> "Hey " + name + ", great to see you!");
    }
}
