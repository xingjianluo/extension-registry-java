package com.github.xingjianluo.extensionregistryjava.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 扩展注册表：负责扩展的注册、装配与运行期启停。
 *
 * <p>使用分两个阶段：</p>
 * <ol>
 *   <li>注册阶段：通过 {@link #register} 声明扩展（描述 + 实例工厂）；</li>
 *   <li>装配阶段：调用 {@link #assemble} 冻结装配结果，之后可通过
 *       {@link #view} 查询并调用扩展，通过 {@link #setEnabled} 运行期启停。</li>
 * </ol>
 *
 * <p>线程安全：注册、装配、查询与启停均可并发调用；
 * 装配结果通过 volatile 一次性发布，调用方不会观察到装配到一半的状态。</p>
 */
public class ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    private final ConcurrentHashMap<String, PointState<?>> points = new ConcurrentHashMap<>();

    /**
     * 注册一个扩展实现的声明。实例工厂在装配期才被调用（惰性初始化），
     * 且每个扩展最多初始化一次。
     */
    public <T extends ExtensionPoint> void register(Class<T> pointType,
                                                    ExtensionDescriptor descriptor,
                                                    Supplier<? extends T> factory) {
        Objects.requireNonNull(pointType, "pointType");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(factory, "factory");
        PointState<T> state = state(pointType);
        synchronized (state) {
            if (state.assembled) {
                throw new IllegalStateException(
                        "extension point already assembled, registration closed: " + state.pointId);
            }
            state.registrations.add(new Registration<>(descriptor, factory));
            log.debug("[{}] registered extension declaration: {}", state.pointId, descriptor);
        }
    }

    /**
     * 装配指定扩展点：校验重复标识、悬空依赖与依赖成环，
     * 按确定顺序初始化所有扩展，并原子地发布装配结果。
     *
     * @throws ExtensionAssemblyException 装配规则被违反时抛出，原因可区分
     */
    public <T extends ExtensionPoint> ExtensionPointView<T> assemble(Class<T> pointType) {
        PointState<T> state = state(pointType);
        synchronized (state) {
            if (state.assembled) {
                throw new IllegalStateException("extension point already assembled: " + state.pointId);
            }
            List<ExtensionDescriptor> descriptors = state.registrations.stream()
                    .map(Registration::descriptor)
                    .toList();
            checkDuplicateIds(state.pointId, descriptors);
            checkDanglingDependencies(state.pointId, descriptors);
            List<ExtensionDescriptor> sorted = topoSort(state.pointId, descriptors);

            List<ExtensionSlot<T>> slots = new ArrayList<>(sorted.size());
            List<ExtensionFailure> failures = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                ExtensionDescriptor descriptor = sorted.get(i);
                Supplier<? extends T> factory = factoryOf(state, descriptor.id());
                log.info("[{}] assembling slot[{}]: id='{}', order={}, dependsOn={}",
                        state.pointId, i, descriptor.id(), descriptor.order(), descriptor.dependsOn());
                try {
                    T instance = Objects.requireNonNull(factory.get(),
                            "extension factory returned null: " + descriptor.id());
                    slots.add(new ExtensionSlot<>(descriptor, instance));
                } catch (Throwable t) {
                    // 故障隔离：单个扩展初始化失败不影响其它扩展
                    log.warn("[{}] extension '{}' failed to initialize, isolated: {}",
                            state.pointId, descriptor.id(), t.toString());
                    failures.add(new ExtensionFailure(descriptor.id(), t));
                }
            }
            // 原子发布：slots/failures 一次写盘（volatile），调用方只见完整结果
            state.failures = List.copyOf(failures);
            state.slots = List.copyOf(slots);
            state.assembled = true;
            log.info("[{}] assembled {} extension(s) in order {}, {} failure(s): {}",
                    state.pointId, slots.size(),
                    slots.stream().map(s -> s.descriptor.id()).toList(),
                    failures.size(),
                    failures.stream().map(ExtensionFailure::extensionId).toList());
        }
        return view(pointType);
    }

    /** 获取指定扩展点当前的装配视图。 */
    public <T extends ExtensionPoint> ExtensionPointView<T> view(Class<T> pointType) {
        PointState<T> state = state(pointType);
        if (!state.assembled) {
            throw new IllegalStateException("extension point not assembled yet: " + state.pointId);
        }
        return new View<>(state);
    }

    /** 运行期启用或禁用单个扩展；不会触发重复初始化。 */
    public void setEnabled(Class<? extends ExtensionPoint> pointType, String extensionId, boolean enabled) {
        PointState<?> state = state(pointType);
        if (!state.assembled) {
            throw new IllegalStateException("extension point not assembled yet: " + state.pointId);
        }
        ExtensionSlot<?> slot = state.slots.stream()
                .filter(s -> s.descriptor.id().equals(extensionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown extension '" + extensionId + "' for point " + state.pointId));
        if (slot.enabled != enabled) {
            slot.enabled = enabled;
            log.info("[{}] extension '{}' {}", state.pointId, extensionId, enabled ? "enabled" : "disabled");
        }
    }

    /** 指定扩展点下已装配扩展的标识（按装配顺序）。 */
    public List<String> assembledIds(Class<? extends ExtensionPoint> pointType) {
        PointState<?> state = state(pointType);
        if (!state.assembled) {
            throw new IllegalStateException("extension point not assembled yet: " + state.pointId);
        }
        return state.slots.stream().map(s -> s.descriptor.id()).toList();
    }

    // ------------------------------------------------------------------
    // 装配规则校验与排序
    // ------------------------------------------------------------------

    private static void checkDuplicateIds(String pointId, List<ExtensionDescriptor> descriptors) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ExtensionDescriptor d : descriptors) {
            counts.merge(d.id(), 1, Integer::sum);
        }
        List<String> duplicated = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!duplicated.isEmpty()) {
            throw new ExtensionAssemblyException(AssemblyFailureReason.DUPLICATE_ID,
                    "[" + pointId + "] duplicate extension id(s): " + duplicated);
        }
    }

    private static void checkDanglingDependencies(String pointId, List<ExtensionDescriptor> descriptors) {
        Map<String, ExtensionDescriptor> byId = new HashMap<>();
        for (ExtensionDescriptor d : descriptors) {
            byId.put(d.id(), d);
        }
        for (ExtensionDescriptor d : descriptors) {
            for (String dep : d.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new ExtensionAssemblyException(AssemblyFailureReason.DANGLING_DEPENDENCY,
                            "[" + pointId + "] extension '" + d.id()
                                    + "' depends on missing extension '" + dep + "'");
                }
            }
        }
    }

    /**
     * 确定性拓扑排序：依赖项先于依赖者；无依赖约束时按 (order, id) 升序。
     * 同一批输入在任何时刻、任何 JVM 进程中都得到相同结果。
     */
    private static List<ExtensionDescriptor> topoSort(String pointId, List<ExtensionDescriptor> descriptors) {
        Map<String, ExtensionDescriptor> byId = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (ExtensionDescriptor d : descriptors) {
            byId.put(d.id(), d);
            indegree.put(d.id(), d.dependsOn().size());
            for (String dep : d.dependsOn()) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(d.id());
            }
        }
        Comparator<ExtensionDescriptor> byOrderThenId = Comparator
                .comparingInt(ExtensionDescriptor::order)
                .thenComparing(ExtensionDescriptor::id);
        PriorityQueue<ExtensionDescriptor> ready = new PriorityQueue<>(byOrderThenId);
        for (ExtensionDescriptor d : descriptors) {
            if (indegree.get(d.id()) == 0) {
                ready.add(d);
            }
        }
        List<ExtensionDescriptor> sorted = new ArrayList<>(descriptors.size());
        while (!ready.isEmpty()) {
            ExtensionDescriptor d = ready.poll();
            sorted.add(d);
            for (String dependent : dependents.getOrDefault(d.id(), List.of())) {
                int remaining = indegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(byId.get(dependent));
                }
            }
        }
        if (sorted.size() < descriptors.size()) {
            List<String> remaining = descriptors.stream()
                    .map(ExtensionDescriptor::id)
                    .filter(id -> !sorted.contains(byId.get(id)))
                    .sorted()
                    .toList();
            String cycle = findCyclePath(byId, remaining);
            throw new ExtensionAssemblyException(AssemblyFailureReason.CYCLIC_DEPENDENCY,
                    "[" + pointId + "] dependency cycle among " + remaining + ", cycle: " + cycle);
        }
        return sorted;
    }

    /** 在未入列的节点中定位一条成环路径，用于失败诊断。 */
    private static String findCyclePath(Map<String, ExtensionDescriptor> byId, List<String> remaining) {
        Map<String, Integer> visitState = new HashMap<>(); // 0=未访问 1=在栈上 2=已完成
        ArrayDeque<String> stack = new ArrayDeque<>();
        for (String start : remaining) {
            if (visitState.getOrDefault(start, 0) != 0) {
                continue;
            }
            stack.push(start);
            visitState.put(start, 1);
            while (!stack.isEmpty()) {
                String current = stack.peek();
                boolean pushed = false;
                for (String dep : byId.get(current).dependsOn()) {
                    int s = visitState.getOrDefault(dep, 0);
                    if (s == 0) {
                        visitState.put(dep, 1);
                        stack.push(dep);
                        pushed = true;
                        break;
                    } else if (s == 1) {
                        List<String> path = new ArrayList<>(stack);
                        java.util.Collections.reverse(path);
                        int from = path.indexOf(dep);
                        return String.join(" -> ", path.subList(from, path.size())) + " -> " + dep;
                    }
                }
                if (!pushed) {
                    visitState.put(stack.pop(), 2);
                }
            }
        }
        return String.join(" -> ", remaining);
    }

    @SuppressWarnings("unchecked")
    private static <T extends ExtensionPoint> Supplier<? extends T> factoryOf(PointState<T> state, String id) {
        return (Supplier<? extends T>) state.registrations.stream()
                .filter(r -> r.descriptor().id().equals(id))
                .findFirst()
                .orElseThrow()
                .factory();
    }

    @SuppressWarnings("unchecked")
    private <T extends ExtensionPoint> PointState<T> state(Class<? extends ExtensionPoint> pointType) {
        return (PointState<T>) points.computeIfAbsent(pointType.getName(), PointState::new);
    }

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------

    private record Registration<T extends ExtensionPoint>(ExtensionDescriptor descriptor,
                                                          Supplier<? extends T> factory) {
    }

    /** 单个扩展的运行期槽位：实例装配期创建一次，enabled 为运行期开关。 */
    private static final class ExtensionSlot<T extends ExtensionPoint> {
        final ExtensionDescriptor descriptor;
        final T instance;
        volatile boolean enabled = true;

        ExtensionSlot(ExtensionDescriptor descriptor, T instance) {
            this.descriptor = descriptor;
            this.instance = instance;
        }
    }

    private static final class PointState<T extends ExtensionPoint> {
        final String pointId;
        final List<Registration<T>> registrations = new ArrayList<>();
        volatile boolean assembled;
        volatile List<ExtensionSlot<T>> slots = List.of();
        volatile List<ExtensionFailure> failures = List.of();

        PointState(String pointId) {
            this.pointId = pointId;
        }
    }

    /** 装配视图：装配内容不可变，启停状态读取运行期开关。 */
    private static final class View<T extends ExtensionPoint> implements ExtensionPointView<T> {

        private final PointState<T> state;

        View(PointState<T> state) {
            this.state = state;
        }

        @Override
        public String pointId() {
            return state.pointId;
        }

        @Override
        public List<T> active() {
            return state.slots.stream()
                    .filter(s -> s.enabled)
                    .map(s -> s.instance)
                    .toList();
        }

        @Override
        public List<ResolvedExtension<T>> assembled() {
            return state.slots.stream()
                    .map(s -> new ResolvedExtension<>(s.descriptor, s.instance))
                    .toList();
        }

        @Override
        public List<ExtensionFailure> failures() {
            return state.failures;
        }

        @Override
        public boolean isEnabled(String extensionId) {
            return state.slots.stream()
                    .filter(s -> s.descriptor.id().equals(extensionId))
                    .findFirst()
                    .map(s -> s.enabled)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown extension '" + extensionId + "' for point " + state.pointId));
        }

        @Override
        public <R> List<R> invokeAll(Function<T, R> invocation) {
            return active().stream().map(invocation).toList();
        }

        @Override
        public <R> Optional<R> invokeFirst(Function<T, R> invocation) {
            return active().stream().findFirst().map(invocation);
        }
    }
}
