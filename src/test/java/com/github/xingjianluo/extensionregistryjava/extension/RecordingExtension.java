package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用扩展：可声明 id/扩展点/顺序/依赖，能模拟初始化失败，
 * 并记录初始化与执行次数以验证"一次性初始化""禁用不执行"等语义。
 */
public class RecordingExtension implements Extension<String, String> {

    private final String id;
    private final String point;
    private final int order;
    private final Set<String> dependencies;
    private final boolean failOnInit;
    private final AtomicInteger initCount = new AtomicInteger();
    private final AtomicInteger executeCount = new AtomicInteger();

    public RecordingExtension(String id, String point, int order, Set<String> dependencies,
                              boolean failOnInit) {
        this.id = id;
        this.point = point;
        this.order = order;
        this.dependencies = new LinkedHashSet<>(dependencies);
        this.failOnInit = failOnInit;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String point() {
        return point;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public Set<String> dependencies() {
        return Set.copyOf(dependencies);
    }

    @Override
    public void initialize() throws Exception {
        initCount.incrementAndGet();
        if (failOnInit) {
            throw new IllegalStateException("模拟初始化失败: " + id);
        }
    }

    @Override
    public String execute(String input) {
        executeCount.incrementAndGet();
        return id + ":" + input;
    }

    public int initCount() {
        return initCount.get();
    }

    public int executeCount() {
        return executeCount.get();
    }
}
