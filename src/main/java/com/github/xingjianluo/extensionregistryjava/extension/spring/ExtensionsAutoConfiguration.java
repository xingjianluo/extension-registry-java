package com.github.xingjianluo.extensionregistryjava.extension.spring;

import com.github.xingjianluo.extensionregistryjava.extension.Extension;
import com.github.xingjianluo.extensionregistryjava.extension.ExtensionRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/**
 * 扩展机制 Spring 装配（骨架）。
 */
@Configuration
@EnableConfigurationProperties(ExtensionProperties.class)
public class ExtensionsAutoConfiguration {

    @Bean
    public ExtensionRegistry extensionRegistry() {
        return new ExtensionRegistry();
    }

    @Bean
    public ExtensionRegistrar extensionRegistrar(ExtensionRegistry registry,
                                                 List<Extension<?, ?>> extensions,
                                                 ExtensionProperties properties) {
        return new ExtensionRegistrar(registry, extensions, properties);
    }
}
