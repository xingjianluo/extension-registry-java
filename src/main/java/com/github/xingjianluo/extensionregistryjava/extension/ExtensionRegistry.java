package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 注册、运行期增删由 {@link ReentrantLock} 串行化；对外可见状态保存在不可变快照中并以
 * {@code volatile} 发布，注册/增删与调用可并发，查询始终看到某一次完整装配的一致视图。
 * 所有扩展仅在装配时初始化一次；{@link #setEnabled(String, boolean)} 只切换开关，
 * 不重复初始化、不清理或重建任何扩展状态。
 *
 * <h3>运行期动态增删</h3>
 * <ul>
 *   <li>{@link #addAll(List)}：原子新增，校验或初始化任一失败整批回滚，调整前视图照常可用；
 *       既有扩展不重复初始化、启用状态保留；</li>
 *   <li>{@link #unregister(String)} / {@link #unregisterAll(List)}：原子下线，从查询与调用中
 *       彻底消失；仍被保留扩展依赖时以 {@link ExtensionInUseException} 拒绝整批；</li>
 *   <li>同一 id 移除后再加入一律按“新实例”语义：必须重新提交扩展对象并重新初始化，
 *       注册表不缓存已下线实例，不存在同一标识两个实例或状态残留；</li>
 *   <li>调整后的装配顺序与启动期静态装配使用同一套确定性规则（依赖优先 → 声明顺序 → id）。</li>
 * </ul>
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
     * 启动期/编程式注册：校验、排序并初始化一批扩展，随后以不可变快照原子发布。
     *
     * <p>保留既有语义：新增扩展初始化失败采用<b>故障隔离</b>——失败扩展标记为
     * {@link ExtensionState#FAILED} 强制禁用，其余扩展照常装配。
     *
     * @throws DuplicateExtensionException 存在重复扩展标识
     * @throws DanglingDependencyException 存在未注册的依赖
     * @throws DependencyCycleException    依赖关系成环
     */
    public void registerAll(List<? extends Extension<?, ?>> extensions) {
        commitAdditions(extensions, true);
    }

    /**
     * 运行期原子新增一批扩展：新增者按与启动期完全相同的依赖与顺序规则参与装配。
     *
     * <p>与 {@link #registerAll} 的唯一差别是失败策略：运行期调整必须保证调用方只能看到
     * “调整前”或“调整后”其中一个完整视图，因此本方法采用<b>整批事务</b>语义——
     * 校验（重复 id / 悬空依赖 / 依赖环）或任一新增扩展初始化失败时，整批不发布，
     * 调整前视图保持原样可用，已尝试初始化的实例被丢弃（不进入注册表）。
     *
     * @throws DuplicateExtensionException      存在重复扩展标识（批内或与已注册重复）
     * @throws DanglingDependencyException      存在未注册的依赖
     * @throws DependencyCycleException         新增后依赖关系成环
     * @throws ExtensionInitializationException 新增扩展初始化失败，整批回滚
     */
    public void addAll(List<? extends Extension<?, ?>> extensions) {
        commitAdditions(extensions, false);
    }

    /**
     * 运行期原子新增单个扩展，语义等同于 {@link #addAll(List)} 传入单元素列表。
     */
    public void add(Extension<?, ?> extension) {
        addAll(List.of(extension));
    }

    /**
     * 运行期原子下线一批扩展（也可经 {@link #unregister(String)} 单个下线）。
     *
     * <p>下线后，这些扩展从其扩展点的查询与调用结果中彻底消失；未被影响的扩展实例、
     * 初始化状态与启用开关一律保留，不重复初始化。装配顺序对剩余扩展重新按统一规则计算。
     *
     * <p>依赖保护：只要仍有<b>保留下来的</b>扩展依赖某个待下线扩展，本次整批请求被拒绝
     * （{@link ExtensionInUseException}），不做任何部分下线，依赖关系不会被破坏。
     * 同一扩展之后重新加入时按“新实例”处理：需要重新注册一个扩展对象并重新初始化，
     * 旧对象及其状态不会被复用或残留（见 {@link #addAll}）。
     *
     * @throws ExtensionNotRegisteredException 待下线 id 未注册或已下线
     * @throws IllegalArgumentException        下线列表内出现重复 id
     * @throws ExtensionInUseException         待下线扩展仍被保留扩展依赖
     */
    public void unregisterAll(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        List<String> removals = new ArrayList<>(ids);
        for (String id : removals) {
            Objects.requireNonNull(id, "extension id");
        }

        lock.lock();
        try {
            Snapshot current = this.snapshot;

            Set<String> removalSet = new LinkedHashSet<>();
            for (String id : removals) {
                if (!removalSet.add(id)) {
                    throw new IllegalArgumentException("下线请求中存在重复的扩展标识: '" + id + "'");
                }
                if (!current.byId.containsKey(id)) {
                    throw new ExtensionNotRegisteredException(
                            "扩展标识未注册或已下线，无法下线: '" + id + "'", id);
                }
            }

            // 依赖保护：对每个待下线扩展，找出仍然保留且依赖它的扩展；非空即拒绝整批。
            for (String id : removalSet) {
                List<String> dependents = new ArrayList<>();
                for (String other : current.order) {
                    if (removalSet.contains(other)) {
                        continue; // 同批一起下线的扩展之间不算“仍在依赖”
                    }
                    if (current.byId.get(other).dependencies().contains(id)) {
                        dependents.add(other);
                    }
                }
                if (!dependents.isEmpty()) {
                    throw new ExtensionInUseException(
                            "扩展 '" + id + "' 仍被已注册扩展 " + dependents
                                    + " 依赖，拒绝下线（请先下线依赖方）", id, dependents);
                }
            }

            Map<String, Extension<?, ?>> mergedExtensions = new LinkedHashMap<>(current.byId);
            Map<String, Boolean> mergedEnabled = new LinkedHashMap<>(current.enabled);
            Map<String, InitOutcome> mergedOutcomes = new LinkedHashMap<>(current.outcomes);
            for (String id : removalSet) {
                mergedExtensions.remove(id);
                mergedEnabled.remove(id);
                mergedOutcomes.remove(id);
            }

            // 兜底校验：经依赖保护后理论上不会再出现悬空依赖，这里再统一校验一次。
            checkDangling(mergedExtensions);
            List<String> order = topoSort(mergedExtensions);

            Snapshot next = publish(order, mergedExtensions, mergedOutcomes, mergedEnabled);
            log.info("运行期下线完成: 移除 {} 个扩展 {}，剩余 {} 个；未受影响扩展保持原实例/状态/开关",
                    removalSet.size(), removalSet, next.order.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 运行期下线单个扩展，语义同 {@link #unregisterAll(List)}。
     */
    public void unregister(String id) {
        unregisterAll(List.of(Objects.requireNonNull(id, "id")));
    }

    private void commitAdditions(List<? extends Extension<?, ?>> extensions,
                                 boolean isolateInitFailures) {
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

            Map<String, Extension<?, ?>> mergedExtensions = new LinkedHashMap<>(current.byId);
            Map<String, Boolean> mergedEnabled = new LinkedHashMap<>(current.enabled);
            for (Extension<?, ?> e : incoming) {
                if (mergedExtensions.containsKey(e.id())) {
                    throw new DuplicateExtensionException(
                            "扩展标识重复: '" + e.id() + "'，重复标识会被拒绝，不会静默覆盖");
                }
                mergedExtensions.put(e.id(), e);
                mergedEnabled.putIfAbsent(e.id(), Boolean.TRUE);
            }

            checkDangling(mergedExtensions);
            List<String> order = topoSort(mergedExtensions);

            // 仅初始化新增扩展；既有扩展保持原实例与状态，绝不重复初始化。
            Map<String, InitOutcome> outcomes = new LinkedHashMap<>(current.outcomes);
            for (String id : order) {
                if (current.byId.containsKey(id)) {
                    continue;
                }
                Extension<?, ?> extension = mergedExtensions.get(id);
                InitOutcome outcome = initializeOne(extension);
                if (outcome.state() == ExtensionState.FAILED) {
                    if (isolateInitFailures) {
                        // 启动期语义：故障隔离，失败扩展强制禁用，其它扩展照常。
                        mergedEnabled.put(id, Boolean.FALSE);
                    } else {
                        // 运行期语义：整批回滚，快照保持调整前视图，已初始化实例一并丢弃。
                        log.error("运行期新增批次中扩展 '{}' 初始化失败，整批未生效（回滚到调整前视图）: {}",
                                id, outcome.failure());
                        throw new ExtensionInitializationException(
                                "运行期新增扩展 '" + id + "' 初始化失败，整批新增未生效，注册表保持调整前状态",
                                id, outcome.failure());
                    }
                }
                outcomes.put(id, outcome);
            }

            Snapshot next = publish(order, mergedExtensions, outcomes, mergedEnabled);
            log.info("运行期新增完成: 新增 {} 个扩展 {}，当前共 {} 个；既有扩展未重复初始化、开关状态保留",
                    incoming.size(),
                    incoming.stream().map(Extension::id).toList(),
                    next.order.size());
        } finally {
            lock.unlock();
        }
    }

    private void checkDangling(Map<String, Extension<?, ?>> mergedExtensions) {
        for (Extension<?, ?> e : mergedExtensions.values()) {
            for (String dep : e.dependencies()) {
                if (!mergedExtensions.containsKey(dep)) {
                    throw new DanglingDependencyException(
                            "扩展 '" + e.id() + "' 声明的依赖 '" + dep + "' 未注册（悬空依赖）");
                }
            }
        }
    }

    private List<String> topoSort(Map<String, Extension<?, ?>> mergedExtensions) {
        List<TopologicalSorter.SortableExtension> sortable = new ArrayList<>();
        for (Extension<?, ?> e : mergedExtensions.values()) {
            sortable.add(new TopologicalSorter.SortableExtension(
                    e.id(), e.order(), Set.copyOf(e.dependencies())));
        }
        return TopologicalSorter.sort(sortable);
    }

    private Snapshot publish(List<String> order,
                             Map<String, Extension<?, ?>> mergedExtensions,
                             Map<String, InitOutcome> outcomes,
                             Map<String, Boolean> mergedEnabled) {
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
        return next;
    }

    /**
     * 基于同一份不可变快照返回某扩展点上的“描述 + 实例”绑定，供调用器使用，
     * 避免描述列表与实例取自两次不同快照。
     */
    List<ResolvedExtension> resolveExtensions(String point) {
        Snapshot s = this.snapshot;
        List<ResolvedExtension> result = new ArrayList<>();
        for (String id : s.byPoint.getOrDefault(point, List.of())) {
            result.add(new ResolvedExtension(describe(s, id), s.byId.get(id)));
        }
        return List.copyOf(result);
    }

    /** 描述与实例绑定在同一份快照内。 */
    record ResolvedExtension(ExtensionDescriptor descriptor, Extension<?, ?> extension) {
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
