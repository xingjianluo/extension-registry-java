package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.List;

/**
 * 依赖成环（骨架）。
 */
public class DependencyCycleException extends ExtensionAssemblyException {

    private final transient List<String> cycle;

    public DependencyCycleException(String message, List<String> cycle) {
        super(message);
        this.cycle = List.copyOf(cycle);
    }

    public List<String> cycle() {
        return cycle;
    }
}
