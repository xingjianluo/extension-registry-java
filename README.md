# 可插拔扩展点装配与运行期开关机制

在 Spring Boot 工程内提供一套扩展点（Extension Point）框架：同一扩展点下可注册多个实现，
框架按**确定且可复现**的规则完成校验、排序、初始化，并支持运行期单独启用/禁用，
同时保证故障隔离与并发安全。

核心代码位于包 `com.github.xingjianluo.extensionregistryjava.extension`，
Spring 集成位于其 `spring` 子包。

---

## 1. 扩展点契约

一个扩展实现实现 `Extension<I, O>` 接口：

| 方法 | 含义 |
| --- | --- |
| `id()` | 扩展**全局唯一标识**，不可重复 |
| `point()` | 所属扩展点标识；同一 point 下的扩展构成一组 |
| `order()` | 声明顺序（int，升序），用于无依赖约束时的稳定排序 |
| `dependencies()` | 依赖的其它扩展 id 集合；被依赖者一定先装配、先初始化 |
| `initialize()` | 装配阶段调用，**整个生命周期只调用一次**；抛错即视为初始化失败 |
| `execute(I)` | 扩展点调用时执行的业务逻辑 |

实现类只需声明为 Spring Bean（被 Spring 扫描到即可），`ExtensionRegistrar`
会在启动时收集容器内全部 `Extension` Bean 统一装配，**不依赖 Bean 的发现顺序**
（收集后先按 id 排序，再由拓扑排序确定最终次序）。也可以脱离 Spring 直接使用
`ExtensionRegistry` 编程式注册。

---

## 2. 装配顺序与依赖规则

装配次序由 `TopologicalSorter` 计算，判定依据（全部确定性，可复现）：

1. **依赖优先**：被依赖的扩展一定排在依赖方之前，初始化也按此顺序进行；
2. **声明顺序**：不存在先后约束的两个扩展，按 `order()` 升序；
3. **id 破平**：`order()` 相同时按 `id()` 字典序。

以下三类非法装配在**任何状态对外可见之前**被拒绝，异常类型彼此可区分，
失败批次整体不生效（不会出现"装配一半"的可见状态，也不会静默覆盖）：

| 场景 | 异常 | 可区分信息 |
| --- | --- | --- |
| 扩展 id 重复（批内或与已注册重复） | `DuplicateExtensionException` | 重复的 id |
| 依赖了不存在的 id | `DanglingDependencyException` | 扩展 id 与缺失依赖 id |
| 依赖成环（含自依赖） | `DependencyCycleException` | `cycle()` 返回环上 id 路径 |

在 Spring 环境下，这些异常会导致应用上下文启动失败并在根因中携带上述信息。

---

## 3. 初始化故障隔离

- 初始化严格按拓扑序逐个执行，**每个扩展只初始化一次**；
- 单个扩展 `initialize()` 抛错时，仅该扩展被标记为 `FAILED`、记录失败原因并强制禁用，
  其它扩展（包括依赖它的扩展）仍照常初始化、可被调用；
- `FAILED` 扩展永远不会被调用，且尝试 `setEnabled(id, true)` 会抛
  `IllegalStateException`，无法被误启用。

---

## 4. 启用 / 禁用语义（运行期开关）

- 初始启用状态来自配置 `extension.enabled.<id>`，**未配置默认为启用**；
  `FAILED` 扩展无论配置为何都被强制禁用；
- 运行期通过 `ExtensionRegistry#setEnabled(id, true|false)` 切换：
  - 只切换一个开关位，**不触发初始化、销毁或任何惰性副作用**，因此反复切换幂等，
    无重复初始化、无状态残留；
  - 切换以不可变快照原子发布，并发调用期间始终读到某一次完整一致的视图；
  - 对未注册 id 切换抛 `IllegalArgumentException`。
- 调用语义（`ExtensionInvoker.invokeAll(registry, point, input)`）：
  - **被禁用扩展不执行**，返回 `enabled=false, value=null, error=null` 的稳定结果；
  - `FAILED` 扩展返回 `state=FAILED` 并携带失败原因；
  - 单个扩展 `execute()` 抛错被捕获进该条结果的 `error` 字段，不影响后续扩展，
    也不向调用方抛出；
  - 返回列表与该扩展点上的扩展一一对应、顺序即装配顺序。
  - 只需要成功结果时使用 `invokeEnabled(...)`。

调用期间获取一次快照，调用过程中发生注册或开关切换不影响本次调用结果。

---

## 5. 并发安全

- 注册由 `ReentrantLock` 串行化，对外状态保存在不可变快照中、以 `volatile` 发布；
- 注册、开关切换可与查询、调用并发；查询结果始终一致，不出现重复实例；
- 并发 40 线程注册 40 个扩展的场景下，每个扩展恰好一个实例、恰好初始化一次，
  最终顺序与注册到达顺序无关（只由规则决定）。

---

## 6. 配置项

```properties
# extension.enabled.<扩展id>=true|false
# 缺省 = true（启用）；初始化失败的扩展强制禁用
extension.enabled.some-extension=false
```

配置指向不存在的扩展 id 时启动日志会给出明确告警（不会静默忽略）。

---

## 7. 使用示例

定义两个扩展，`discount-vip` 依赖 `discount-base`：

```java
@Component
public class BaseDiscount implements Extension<Order, Double> {
    public String id() { return "discount-base"; }
    public String point() { return "price-discount"; }
    public int order() { return 100; }
    public Set<String> dependencies() { return Set.of(); }
    public void initialize() { }
    public Double execute(Order order) { return order.amount() * 0.95; }
}

@Component
public class VipDiscount implements Extension<Order, Double> {
    public String id() { return "discount-vip"; }
    public String point() { return "price-discount"; }
    public int order() { return 10; }
    public Set<String> dependencies() { return Set.of("discount-base"); }
    public void initialize() { }
    public Double execute(Order order) { return order.amount() * 0.9; }
}
```

注入注册表后按装配顺序调用、运行期切换：

```java
@Autowired ExtensionRegistry registry;

// 按装配顺序调用全部可用扩展（禁用/失败扩展返回稳定结果，不抛异常）
List<ExtensionResult<Double>> results =
        ExtensionInvoker.invokeAll(registry, "price-discount", order);

// 只要成功扩展的输出
List<Double> prices = ExtensionInvoker.invokeEnabled(registry, "price-discount", order);

// 运行期禁用某个扩展（幂等、无副作用）
registry.setEnabled("discount-vip", false);
```

---

## 8. 可观测性

每次装配后通过 SLF4J 打印装配顺序与判定依据（装配位、id、扩展点、声明顺序、
依赖集合、最终状态、启用位）；`ExtensionRegistry#getLastAssemblyReport()`
返回结构化的 `AssemblyReport`（含每条 `AssemblyRecord` 的判定原因）。
单测日志同时重定向落盘到 `target/surefire-reports/*-output.txt`。

---

## 9. 测试覆盖

| 测试类 | 覆盖场景 |
| --- | --- |
| `AssemblyOrderingTest` | 声明顺序、id 破平、依赖优先、重复标识、悬空依赖、依赖成环（含自环）、失败后无部分装配 |
| `LifecycleAndIsolationTest` | 初始化失败隔离、禁用稳定结果、反复切换不重复初始化/无残留、失败扩展调用语义、未知 id 拒绝 |
| `ConcurrentAccessTest` | 并发注册无重复实例且只初始化一次、并发调用+切换快照一致 |
| `SpringIntegrationTest` | Bean 收集装配、配置驱动开关、失败隔离、重复 id 启动失败原因可区分 |
