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
 * 注册与运行期增减由同一把 {@link ReentrantLock} 串行化；对外可见状态保存在不可变快照中
 * 并以 {@code volatile} 发布，注册、动态增减、开关切换与查询、调用均可并发，查询始终看到
 * 某一次完整装配的一致视图。所有扩展仅在装配时初始化一次；
 * {@link #setEnabled(String, boolean)} 只切换开关，不重复初始化、不清理或重建任何扩展状态。
 *
 * <h3>运行期动态增减</h3>
 * <ul>
 *   <li>{@link #add(Extension)} / {@link #addAll(List)}：新扩展按与启动期相同的依赖、
 *       声明顺序规则参与装配并可被调用；严格原子——重复 id、悬空依赖、依赖成环或任一
 *       新扩展初始化失败，整批调整回滚，调整前视图照常可用；</li>
 *   <li>{@link #remove(String)} / {@link #removeAll(List)}：被下线扩展从该扩展点的查询、
 *       调用、开关与装配报告中彻底消失；只要还有存活扩展依赖它，下线即被
 *       {@link ExtensionInUseException} 拒绝，绝不留下半生效状态；</li>
 *   <li>未参与调整的扩展沿用原实例、原初始化结果与原 enabled 位，不重复初始化；</li>
 *   <li>"移除后再加回"语义定死：移除即彻底丢弃实例与全部状态（含 enabled、初始化结果），
 *       再加回一律按全新实例重新初始化、enabled 重置为默认启用。任何时刻同一 id 至多一个实例；</li>
 *   <li>调整后的装配顺序由 {@link TopologicalSorter} 对最终集合重新计算，规则与启动期
 *       静态装配完全一致，不随中间是否动态变动而漂移。</li>
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

            // 3. 确定性拓扑排序（与运行期动态增减共用同一套规则；内部检测依赖环）。
            List<String> order = sortAll(mergedExtensions);

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

            // 5. 构建并原子发布不可变快照（与动态增减共用同一条发布路径）。
            publish(order, mergedExtensions, outcomes, mergedEnabled);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 运行期新增单个扩展，语义等同于 {@link #addAll(List)} 传入单元素列表。
     */
    public void add(Extension<?, ?> extension) {
        addAll(List.of(extension));
    }

    /**
     * 运行期批量新增扩展，严格原子地参与装配：
     * <ol>
     *   <li>新扩展与现存扩展之间出现重复 id，抛 {@link DuplicateExtensionException}；</li>
     *   <li>新扩展依赖了装配后仍不存在的 id，抛 {@link DanglingDependencyException}；</li>
     *   <li>合并后依赖关系成环，抛 {@link DependencyCycleException}；</li>
     *   <li>任一新扩展 {@code initialize()} 失败，整批新增回滚，抛
     *       {@link DynamicReconfigurationException}，调整前视图照常可用；</li>
     *   <li>全部成功后，新扩展按同一套依赖/声明顺序规则参与装配并可被查询、调用；
     *       既有扩展沿用原实例、原初始化结果与原 enabled 位，不重复初始化。</li>
     * </ol>
     * 任何失败都发生在新快照发布之前，调用方不可能观察到"加了一半"的中间视图。
     */
    public void addAll(List<? extends Extension<?, ?>> extensions) {
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

            // 1. 重复标识校验（批内 + 与现存扩展）。
            Map<String, Extension<?, ?>> mergedExtensions = new LinkedHashMap<>(current.byId);
            for (Extension<?, ?> e : incoming) {
                if (mergedExtensions.containsKey(e.id())) {
                    throw new DuplicateExtensionException(
                            "运行期新增失败：扩展标识 '" + e.id()
                                    + "' 已存在，重复标识会被拒绝（请先下线再新增），本次调整不生效");
                }
                mergedExtensions.put(e.id(), e);
            }

            // 2. 悬空依赖校验（依赖必须能在合并后的集合中解析到）。
            for (Extension<?, ?> e : incoming) {
                for (String dep : e.dependencies()) {
                    if (!mergedExtensions.containsKey(dep)) {
                        throw new DanglingDependencyException(
                                "运行期新增失败：扩展 '" + e.id() + "' 声明的依赖 '"
                                        + dep + "' 未注册（悬空依赖），本次调整不生效");
                    }
                }
            }

            // 3. 对合并后的全量集合重跑同一套确定性拓扑排序（内部检测依赖环）。
            List<String> order = sortAll(mergedExtensions);

            // 4. 仅初始化新扩展，按拓扑序进行；严格原子——任一失败立即中止、不发布。
            //    已完成初始化的同批其它新扩展因快照未发布而不可见，随后被丢弃。
            Map<String, InitOutcome> nextOutcomes = new LinkedHashMap<>(current.outcomes);
            for (String id : order) {
                if (current.byId.containsKey(id)) {
                    continue; // 既有扩展不重复初始化
                }
                InitOutcome outcome = initializeOne(mergedExtensions.get(id));
                if (outcome.state() == ExtensionState.FAILED) {
                    throw new DynamicReconfigurationException(
                            "运行期新增失败：新扩展 '" + id + "' 初始化失败，本次调整已整体回滚，"
                                    + "调整前装配视图继续可用: " + outcome.failure(),
                            id, outcome.failure());
                }
                nextOutcomes.put(id, outcome);
            }

            // 5. 既有 enabled 位原样保留；新扩展缺省启用。
            Map<String, Boolean> nextEnabled = new LinkedHashMap<>(current.enabled);
            for (Extension<?, ?> e : incoming) {
                nextEnabled.putIfAbsent(e.id(), Boolean.TRUE);
            }

            // 6. 全部成功，原子发布。
            Snapshot next = publish(order, mergedExtensions, nextOutcomes, nextEnabled);
            log.info("运行期新增完成: added={}，既有扩展未重复初始化，最新装配顺序={}",
                    incoming.stream().map(Extension::id).toList(), next.order);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 运行期下线单个扩展，语义等同于 {@link #removeAll(List)} 传入单元素列表。
     */
    public void remove(String id) {
        removeAll(List.of(id));
    }

    /**
     * 运行期批量下线扩展，原子生效：
     * <ol>
     *   <li>任一 id 未注册，整个请求被拒绝（{@link IllegalArgumentException}），视图不变；</li>
     *   <li>仍有现存扩展依赖待下线扩展时，整个请求被拒绝
     *       （{@link ExtensionInUseException}），不破坏任何依赖关系，视图不变；</li>
     *   <li>校验通过后，待下线扩展从查询、调用、开关与装配报告中彻底消失，
     *       其实例与所有状态（初始化结果、enabled 位）一并丢弃；</li>
     *   <li>保留下来的扩展沿用原实例、原初始化结果与原 enabled 位，不重复初始化；
     *       新装配顺序由同一套确定性规则对剩余集合重新计算。</li>
     * </ol>
     */
    public void removeAll(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        List<String> requested = ids.stream().distinct().toList();
        for (String id : requested) {
            Objects.requireNonNull(id, "extension id");
        }

        lock.lock();
        try {
            Snapshot current = this.snapshot;

            // 1. 全部待下线 id 必须存在，否则整批拒绝（不做部分下线）。
            for (String id : requested) {
                if (!current.byId.containsKey(id)) {
                    throw new IllegalArgumentException(
                            "下线失败：扩展标识 '" + id + "' 未注册，本次调整不生效");
                }
            }
            Set<String> removing = new LinkedHashSet<>(requested);

            // 2. 依赖保护：仅检查"下线后仍然存活"的扩展；它们不得依赖任何被下线扩展。
            //    待下线扩展之间的相互依赖随其一起消失，不构成保护理由。
            Map<String, List<String>> blockedBy = new LinkedHashMap<>();
            for (Extension<?, ?> survivor : current.byId.values()) {
                if (removing.contains(survivor.id())) {
                    continue;
                }
                for (String dep : survivor.dependencies()) {
                    if (removing.contains(dep)) {
                        blockedBy.computeIfAbsent(dep, k -> new ArrayList<>()).add(survivor.id());
                    }
                }
            }
            if (!blockedBy.isEmpty()) {
                blockedBy.values().forEach(Collections::sort);
                throw new ExtensionInUseException(
                        "下线被拒绝：以下扩展仍被现存扩展依赖（依赖关系不会被破坏，本次调整不生效）: "
                                + blockedBy, blockedBy);
            }

            // 3. 组装下线后的集合：保留实例/初始化结果/enabled 位，丢弃被下线扩展的全部状态。
            Map<String, Extension<?, ?>> nextById = new LinkedHashMap<>();
            Map<String, InitOutcome> nextOutcomes = new LinkedHashMap<>();
            Map<String, Boolean> nextEnabled = new LinkedHashMap<>();
            for (Map.Entry<String, Extension<?, ?>> entry : current.byId.entrySet()) {
                String id = entry.getKey();
                if (removing.contains(id)) {
                    continue;
                }
                nextById.put(id, entry.getValue());
                nextOutcomes.put(id, current.outcomes.get(id));
                nextEnabled.put(id, current.enabled.get(id));
            }

            // 4. 对剩余集合按同一套确定性规则重新装配排序。
            List<String> order = sortAll(nextById);

            // 5. 原子发布不可变快照；整个过程没有任何扩展被重新初始化。
            Snapshot next = publish(order, nextById, nextOutcomes, nextEnabled);
            log.info("运行期下线完成: removed={}，保留扩展未重复初始化，重新计算后的装配顺序={}",
                    requested, next.order);
        } finally {
            lock.unlock();
        }
    }

    private List<String> sortAll(Map<String, Extension<?, ?>> byId) {
        List<TopologicalSorter.SortableExtension> sortable = new ArrayList<>();
        for (Extension<?, ?> e : byId.values()) {
            sortable.add(new TopologicalSorter.SortableExtension(
                    e.id(), e.order(), Set.copyOf(e.dependencies())));
        }
        return TopologicalSorter.sort(sortable);
    }

    private Snapshot publish(List<String> order,
                             Map<String, Extension<?, ?>> byId,
                             Map<String, InitOutcome> outcomes,
                             Map<String, Boolean> enabled) {
        Map<String, List<String>> byPoint = new LinkedHashMap<>();
        for (String id : order) {
            String point = byId.get(id).point();
            byPoint.computeIfAbsent(point, k -> new ArrayList<>()).add(id);
        }
        Snapshot next = new Snapshot(
                List.copyOf(order),
                Collections.unmodifiableMap(byId),
                Collections.unmodifiableMap(byPoint),
                Collections.unmodifiableMap(outcomes),
                Collections.unmodifiableMap(enabled));
        this.snapshot = next;
        logAssembly(next);
        return next;
    }

    private InitOutcome initializeOne(Extension<?, ?> extension) {
        try {
            extension.initialize();
            log.info("扩展初始化成功: id='{}' point='{}' declaredOrder={}",
                    extension.id(), extension.point(), extension.order());
            return new InitOutcome(ExtensionState.INITIALIZED, null);
        } catch (Throwable t) {
            log.error("扩展初始化失败: id='{}' point='{}' reason={}（静态装配时隔离该扩展；"
                    + "运行期动态新增时整批回滚、不发布）",
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
     * 返回某扩展点上"描述 + 实例"的同快照配对视图。描述与实例取自同一份不可变快照，
     * 供调用器在并发调整期间保证一次调用内视图一致，不会先查到描述、再拿到已被下线的实例。
     */
    public List<ResolvedExtension> getResolvedExtensions(String point) {
        Snapshot s = this.snapshot;
        List<ResolvedExtension> result = new ArrayList<>();
        for (String id : s.byPoint.getOrDefault(point, List.of())) {
            result.add(new ResolvedExtension(describe(s, id), s.byId.get(id)));
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
