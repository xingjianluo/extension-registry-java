package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扩展点注册表：负责扩展的注册、装配排序、一次性初始化以及运行期启用/禁用。
 *
 * <h3>装配与校验</h3>
 * <ul>
 *   <li>同一批及与已注册扩展之间出现重复 id，抛 {@link DuplicateExtensionException}；</li>
 *   <li>依赖了不存在的 id，抛 {@link DanglingDependencyException}；</li>
 *   <li>依赖关系成环，抛 {@link DependencyCycleException}（携带环路径）；</li>
 *   <li>以上错误均在任何状态对外可见之前抛出，调用方不会观察到装配到一半的状态。</li>
 * </ul>
 *
 * <h3>故障隔离</h3>
 * 初始化按拓扑序逐个执行，单个扩展 {@code initialize()} 抛错只将该扩展标记为
 * {@link ExtensionState#FAILED} 并记录原因，其余扩展（包括依赖它的扩展）照常初始化，
 * 故障扩展永远不可被启用、不可被调用。
 *
 * <h3>并发安全与稳定性</h3>
 * 注册由 {@link ReentrantLock} 串行化；对外可见状态保存在不可变快照中并以
 * {@code volatile} 发布，注册与调用可并发，查询始终看到某一次完整装配的一致视图。
 * 所有扩展仅在装配时初始化一次；{@link #setEnabled(String, boolean)} 只切换开关，
 * 不重复初始化、不清理或重建任何扩展状态。
 */
public final class ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    private final ReentrantLock lock = new ReentrantLock();
    private volatile Snapshot snapshot = Snapshot.empty();

    public ExtensionRegistry() {
    }

    /**
     * 注册单个扩展，语义等同于 {@link #registerAll(List)} 传入单元素列表。
     */
    public void register(Extension<?, ?> extension) {
        registerAll(List.of(extension));
    }

    /**
     * 校验、排序并初始化一批扩展，随后以不可变快照原子发布。
     *
     * @throws DuplicateExtensionException 存在重复扩展标识
     * @throws DanglingDependencyException 存在未注册的依赖
     * @throws DependencyCycleException    依赖关系成环
     */
    public void registerAll(List<? extends Extension<?, ?>> extensions) {
        Objects.requireNonNull(extensions, "extensions");
        List<Extension<?, ?>> incoming = new ArrayList<>(extensions);
        for (Extension<?, ?> e : incoming) {
            Objects.requireNonNull(e, "extension");
            Objects.requireNonNull(e.id(), "extension id");
            Objects.requireNonNull(e.point(), "extension point");
        }

        lock.lock();
        try {
            Snapshot current = this.snapshot;

            // 1. 重复标识校验（批内 + 与已注册集合）。
            Map<String, Boolean> mergedEnabled = new LinkedHashMap<>(current.enabled);
            Map<String, Extension<?, ?>> mergedExtensions = new LinkedHashMap<>(current.byId);
            for (Extension<?, ?> e : incoming) {
                if (mergedExtensions.containsKey(e.id())) {
                    throw new DuplicateExtensionException(
                            "扩展标识重复: '" + e.id() + "'，重复标识会被拒绝，不会静默覆盖");
                }
                mergedExtensions.put(e.id(), e);
                mergedEnabled.putIfAbsent(e.id(), Boolean.TRUE);
            }

            // 2. 悬空依赖校验。
            for (Extension<?, ?> e : incoming) {
                for (String dep : e.dependencies()) {
                    if (!mergedExtensions.containsKey(dep)) {
                        throw new DanglingDependencyException(
                                "扩展 '" + e.id() + "' 声明的依赖 '" + dep + "' 未注册（悬空依赖）");
                    }
                }
            }

            // 3. 确定性拓扑排序（内部检测依赖环）。
            List<TopologicalSorter.SortableExtension> sortable = new ArrayList<>();
            for (Extension<?, ?> e : mergedExtensions.values()) {
                sortable.add(new TopologicalSorter.SortableExtension(
                        e.id(), e.order(), Set.copyOf(e.dependencies())));
            }
            List<String> order = TopologicalSorter.sort(sortable);

            // 4. 逐个初始化，故障隔离；保留既有扩展的既有状态。
            Map<String, InitOutcome> outcomes = new LinkedHashMap<>(current.outcomes);
            for (String id : order) {
                if (current.byId.containsKey(id)) {
                    continue; // 已初始化过的扩展不重复初始化
                }
                Extension<?, ?> extension = mergedExtensions.get(id);
                InitOutcome outcome = initializeOne(extension);
                outcomes.put(id, outcome);
                if (outcome.state() == ExtensionState.FAILED) {
                    // 故障扩展强制禁用，但不影响其它扩展。
                    mergedEnabled.put(id, Boolean.FALSE);
                }
            }

            // 5. 构建并原子发布不可变快照。
            Map<String, List<String>> byPoint = new LinkedHashMap<>();
            for (String id : order) {
                String point = mergedExtensions.get(id).point();
                byPoint.computeIfAbsent(point, k -> new ArrayList<>()).add(id);
            }
            Snapshot next = new Snapshot(
                    List.copyOf(order),
                    Collections.unmodifiableMap(mergedExtensions),
                    Collections.unmodifiableMap(byPoint),
                    Collections.unmodifiableMap(outcomes),
                    Collections.unmodifiableMap(new LinkedHashMap<>(mergedEnabled)));
            this.snapshot = next;
            logAssembly(next);
        } finally {
            lock.unlock();
        }
    }

    private InitOutcome initializeOne(Extension<?, ?> extension) {
        try {
            extension.initialize();
            log.info("扩展初始化成功: id='{}' point='{}' declaredOrder={}",
                    extension.id(), extension.point(), extension.order());
            return new InitOutcome(ExtensionState.INITIALIZED, null);
        } catch (Throwable t) {
            log.error("扩展初始化失败（已隔离，不影响其它扩展）: id='{}' point='{}' reason={}",
                    extension.id(), extension.point(), t.toString());
            return new InitOutcome(ExtensionState.FAILED, t);
        }
    }

    /**
     * 返回某扩展点上"已启用且初始化成功"的扩展，顺序即确定的装配顺序。
     */
    public List<ExtensionDescriptor> getEnabledExtensions(String point) {
        Snapshot s = this.snapshot;
        List<ExtensionDescriptor> result = new ArrayList<>();
        for (String id : s.byPoint.getOrDefault(point, List.of())) {
            ExtensionDescriptor d = describe(s, id);
            if (d.enabled() && d.state() == ExtensionState.INITIALIZED) {
                result.add(d);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 返回某扩展点上的全部扩展（含禁用与初始化失败），顺序即装配顺序。
     */
    public List<ExtensionDescriptor> getExtensions(String point) {
        Snapshot s = this.snapshot;
        List<ExtensionDescriptor> result = new ArrayList<>();
        for (String id : s.byPoint.getOrDefault(point, List.of())) {
            result.add(describe(s, id));
        }
        return List.copyOf(result);
    }

    /**
     * 按 id 取得扩展实例，不存在返回 null。
     */
    @SuppressWarnings("unchecked")
    public <I, O> Extension<I, O> getExtension(String id) {
        return (Extension<I, O>) this.snapshot.byId.get(id);
    }

    /**
     * 返回指定 id 的扩展描述，不存在返回 null。
     */
    public ExtensionDescriptor getDescriptor(String id) {
        Snapshot s = this.snapshot;
        if (!s.byId.containsKey(id)) {
            return null;
        }
        return describe(s, id);
    }

    public boolean isEnabled(String id) {
        Snapshot s = this.snapshot;
        return Boolean.TRUE.equals(s.enabled.get(id));
    }

    /**
     * 运行期切换单个扩展的启用状态。切换只改变开关位，不触发任何初始化或销毁，
     * 因此反复切换幂等、无重复初始化与状态残留。
     *
     * @throws IllegalArgumentException 扩展 id 未注册
     * @throws IllegalStateException    试图启用初始化失败的扩展
     */
    public void setEnabled(String id, boolean enabled) {
        lock.lock();
        try {
            Snapshot s = this.snapshot;
            if (!s.byId.containsKey(id)) {
                throw new IllegalArgumentException("未注册的扩展标识: '" + id + "'");
            }
            InitOutcome outcome = s.outcomes.get(id);
            if (enabled && outcome.state() == ExtensionState.FAILED) {
                throw new IllegalStateException(
                        "扩展 '" + id + "' 初始化失败，不允许启用: " + outcome.failure());
            }
            boolean previous = Boolean.TRUE.equals(s.enabled.get(id));
            if (previous == enabled) {
                log.info("扩展 '{}' 启用状态无变化（仍为 {}），不产生任何副作用", id, enabled);
                return;
            }
            Map<String, Boolean> nextEnabled = new LinkedHashMap<>(s.enabled);
            nextEnabled.put(id, enabled);
            this.snapshot = s.withEnabled(Collections.unmodifiableMap(nextEnabled));
            log.info("扩展 '{}' 运行期开关切换: {} -> {}", id, previous, enabled);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取最近一次装配的完整判定报告（装配顺序、声明顺序、依赖、状态与判定依据）。
     */
    public AssemblyReport getLastAssemblyReport() {
        Snapshot s = this.snapshot;
        List<AssemblyRecord> records = new ArrayList<>();
        for (int i = 0; i < s.order.size(); i++) {
            String id = s.order.get(i);
            Extension<?, ?> e = s.byId.get(id);
            InitOutcome outcome = s.outcomes.get(id);
            String reason = switch (outcome.state()) {
                case INITIALIZED -> Boolean.TRUE.equals(s.enabled.get(id))
                        ? "依赖先于自身完成；装配位 " + i + "，已启用"
                        : "装配位 " + i + "，当前被禁用（调用时返回 DISABLED 稳定结果）";
                case FAILED -> "初始化失败已隔离: " + outcome.failure();
                default -> "未初始化";
            };
            records.add(new AssemblyRecord(i, id, e.point(), e.order(),
                    List.copyOf(e.dependencies()), outcome.state(), reason));
        }
        return new AssemblyReport(records);
    }

    private ExtensionDescriptor describe(Snapshot s, String id) {
        Extension<?, ?> e = s.byId.get(id);
        InitOutcome outcome = s.outcomes.get(id);
        int index = s.order.indexOf(id);
        return new ExtensionDescriptor(id, e.point(), e.order(), e.dependencies(),
                outcome.state(), outcome.failure(),
                Boolean.TRUE.equals(s.enabled.get(id)), index);
    }

    private void logAssembly(Snapshot s) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("========== 扩展装配结果（共 {} 个，顺序即调用顺序） ==========", s.order.size());
        for (int i = 0; i < s.order.size(); i++) {
            String id = s.order.get(i);
            Extension<?, ?> e = s.byId.get(id);
            InitOutcome outcome = s.outcomes.get(id);
            log.info("装配位[{}] id='{}' point='{}' declaredOrder={} dependencies={} state={} enabled={}",
                    i, id, e.point(), e.order(), e.dependencies(), outcome.state(),
                    s.enabled.get(id));
        }
        log.info("判定依据: 被依赖者优先；无约束时 declaredOrder 升序；再相同按 id 字典序。==========");
    }

    private record InitOutcome(ExtensionState state, Throwable failure) {
    }

    private static final class Snapshot {
        final List<String> order;
        final Map<String, Extension<?, ?>> byId;
        final Map<String, List<String>> byPoint;
        final Map<String, InitOutcome> outcomes;
        final Map<String, Boolean> enabled;

        Snapshot(List<String> order,
                 Map<String, Extension<?, ?>> byId,
                 Map<String, List<String>> byPoint,
                 Map<String, InitOutcome> outcomes,
                 Map<String, Boolean> enabled) {
            this.order = order;
            this.byId = byId;
            this.byPoint = byPoint;
            this.outcomes = outcomes;
            this.enabled = enabled;
        }

        static Snapshot empty() {
            return new Snapshot(List.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        Snapshot withEnabled(Map<String, Boolean> newEnabled) {
            return new Snapshot(order, byId, byPoint, outcomes, newEnabled);
        }
    }
}
