package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.Set;

/**
 * 扩展实例的装配视图（骨架）。
 */
public final class ExtensionDescriptor {

    private final String id;
    private final String point;
    private final int declaredOrder;
    private final Set<String> dependencies;
    private final ExtensionState state;
    private final Throwable failure;
    private final boolean enabled;
    private final int assembledIndex;

    public ExtensionDescriptor(String id, String point, int declaredOrder, Set<String> dependencies,
                               ExtensionState state, Throwable failure, boolean enabled, int assembledIndex) {
        this.id = id;
        this.point = point;
        this.declaredOrder = declaredOrder;
        this.dependencies = Set.copyOf(dependencies);
        this.state = state;
        this.failure = failure;
        this.enabled = enabled;
        this.assembledIndex = assembledIndex;
    }

    public String id() { return id; }
    public String point() { return point; }
    public int declaredOrder() { return declaredOrder; }
    public Set<String> dependencies() { return dependencies; }
    public ExtensionState state() { return state; }
    public Throwable failure() { return failure; }
    public boolean enabled() { return enabled; }
    public int assembledIndex() { return assembledIndex; }
}
