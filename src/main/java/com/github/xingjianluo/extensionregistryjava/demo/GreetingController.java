package com.github.xingjianluo.extensionregistryjava.demo;

import com.github.xingjianluo.extensionregistryjava.extension.ExtensionRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 演示端点：经由扩展点调用所有当前可用的问候扩展。
 */
@RestController
public class GreetingController {

    private final ExtensionRegistry registry;

    public GreetingController(ExtensionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/greetings")
    public Map<String, Object> greetings(@RequestParam(defaultValue = "world") String name) {
        var view = registry.view(GreetingExtension.class);
        return Map.of(
                "assembledOrder", view.assembled().stream().map(r -> r.id()).toList(),
                "greetings", view.invokeAll(g -> g.greet(name)));
    }

    @GetMapping("/api/greetings/failures")
    public List<String> failures() {
        return registry.view(GreetingExtension.class).failures().stream()
                .map(f -> f.extensionId() + ": " + f.cause())
                .toList();
    }
}
