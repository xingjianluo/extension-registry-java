package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依据依赖与声明顺序进行稳定拓扑排序。
 *
 * <p>装配次序判定规则（全部可复现，不依赖任何加载顺序）：
 * <ol>
 *   <li>被依赖的扩展一定排在依赖方之前；</li>
 *   <li>无先后约束的两个扩展，按声明 {@code order} 升序排列；</li>
 *   <li>{@code order} 相同则按扩展 {@code id} 字典序排列。</li>
 * </ol>
 * 使用 DFS 三色标记法检测依赖环，命中环时抛出携带环路径的
 * {@link DependencyCycleException}。
 */
public final class TopologicalSorter {

    private TopologicalSorter() {
    }

    /**
     * 参与排序的扩展视图。
     *
     * @param id           扩展唯一标识
     * @param order        声明顺序（升序）
     * @param dependencies 依赖的其它扩展 id 集合
     */
    public record SortableExtension(String id, int order, Set<String> dependencies) {
    }

    private static final Comparator<SortableExtension> ORDERING =
            Comparator.comparingInt(SortableExtension::order)
                    .thenComparing(SortableExtension::id);

    /**
     * 返回满足依赖优先、声明次序稳定的扩展 id 序列。
     *
     * @param extensions 待排序扩展（必须已通过重复标识与悬空依赖校验）
     * @return 装配次序（被依赖者在前）
     * @throws DependencyCycleException 依赖关系存在环时抛出，异常中携带环上 id
     */
    public static List<String> sort(List<SortableExtension> extensions) {
        Map<String, SortableExtension> byId = new HashMap<>();
        for (SortableExtension e : extensions) {
            byId.put(e.id(), e);
        }

        List<SortableExtension> orderedRoots = extensions.stream()
                .sorted(ORDERING)
                .toList();

        Map<String, Integer> color = new HashMap<>();
        for (SortableExtension e : extensions) {
            color.put(e.id(), 0);
        }

        Deque<String> stack = new ArrayDeque<>();
        List<String> result = new ArrayList<>(extensions.size());

        for (SortableExtension root : orderedRoots) {
            if (color.get(root.id()) == 0) {
                visit(root, byId, color, stack, result);
            }
        }
        return result;
    }

    private static void visit(SortableExtension node,
                              Map<String, SortableExtension> byId,
                              Map<String, Integer> color,
                              Deque<String> stack,
                              List<String> result) {
        color.put(node.id(), 1);
        stack.addLast(node.id());

        List<SortableExtension> neighbors = new ArrayList<>();
        for (String dep : node.dependencies()) {
            SortableExtension depNode = byId.get(dep);
            if (depNode == null) {
                // 注册表在校验阶段会先以 DanglingDependencyException 拒绝，这里兜底。
                throw new DanglingDependencyException(
                        "扩展 '" + node.id() + "' 依赖了未注册的扩展 '" + dep + "'");
            }
            neighbors.add(depNode);
        }
        neighbors.sort(ORDERING);

        for (SortableExtension neighbor : neighbors) {
            int neighborColor = color.get(neighbor.id());
            if (neighborColor == 0) {
                visit(neighbor, byId, color, stack, result);
            } else if (neighborColor == 1) {
                throw new DependencyCycleException(
                        "检测到扩展依赖环: " + describeCycle(stack, neighbor.id()),
                        extractCycle(stack, neighbor.id()));
            }
            // color == 2：已完成，跳过
        }

        stack.removeLast();
        color.put(node.id(), 2);
        result.add(node.id());
    }

    private static List<String> extractCycle(Deque<String> stack, String backTo) {
        List<String> path = new ArrayList<>(stack);
        int start = path.indexOf(backTo);
        List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
        cycle.add(backTo);
        return List.copyOf(cycle);
    }

    private static String describeCycle(Deque<String> stack, String backTo) {
        return String.join(" -> ", extractCycle(stack, backTo));
    }
}
