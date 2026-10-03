# 可插拔扩展点装配与运行期开关机制

在 Spring Boot 工程内提供一套扩展点（Extension Point）框架：同一扩展点下可注册多个实现，
框架按**确定且可复现**的规则完成校验、排序、初始化，并支持运行期单独启用/禁用，
以及**运行期动态新增、下线扩展实现（无需重启）**，同时保证故障隔离与并发安全。

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

调用期间获取一次快照，调用过程中发生注册、动态增减或开关切换不影响本次调用结果。

---

## 5. 运行期动态新增 / 下线（无需重启）

在启动期静态装配之外，`ExtensionRegistry` 额外提供运行期动态调整 API，
**与静态装配遵循同一套依赖、顺序与快照规则**：

```java
// 运行期新增（add 为 addAll 的单元素便捷形式）
registry.add(new VipDiscount());
registry.addAll(List.of(ext1, ext2));

// 运行期下线（remove 为 removeAll 的单元素便捷形式）
registry.remove("discount-vip");
registry.removeAll(List.of("id-1", "id-2"));
```

### 5.1 新增语义

- 新扩展按第 2 节的规则（依赖优先 → `order` 升序 → id 字典序）对**合并后的全量集合**
  重新装配，可立即被查询与调用，无需重启；
- 新增是**严格原子、全有或全无**的，下列任一情况整个批次都不生效，调整前视图照常可用、
  扩展点不会瘫痪，调用方也看不到"装到一半"的中间视图：
  - 与现存扩展或批内重复 id → `DuplicateExtensionException`；
  - 依赖了装配后仍不存在的 id → `DanglingDependencyException`；
  - 合并后依赖关系成环 → `DependencyCycleException`；
  - 任一**新**扩展 `initialize()` 抛错 → `DynamicReconfigurationException`
    （携带失败扩展 id 与根因；同批已初始化的其它新扩展因未发布而整体丢弃）。

> 注意与启动期 `registerAll` 的故障隔离语义区分：启动期目标是"尽量可用"，单个扩展初始化
> 失败会被隔离（标记 `FAILED`）而不拖垮整批；运行期动态新增目标是"调整要么完整成功、
> 要么完全不发生"，因此新扩展初始化失败会整批回滚。两者均保证扩展点始终可用。

### 5.2 下线语义

- 下线成功后，该扩展从该扩展点的**查询结果、调用结果、开关位与装配报告中彻底消失**，
  不会留有半生效状态；其运行实例与全部框架侧状态一并丢弃；
- **依赖保护**：只要仍有存活扩展依赖待下线扩展，下线请求被
  `ExtensionInUseException` 直接拒绝（`blockedBy()` 返回"被保护 id → 仍依赖它的 id 列表"）；
  批量下线时若同批 id 之间互相依赖、但它们在本批中一起消失，则允许（存活集合中不再存在
  指向被下线集合的依赖）；
- 待下线集合中包含未注册 id 时抛 `IllegalArgumentException`，整批拒绝。

### 5.3 实例、状态与顺序稳定性（定死的语义）

- **未受影响的扩展不重复初始化**：动态调整只初始化新扩展，既有扩展沿用原实例、
  原初始化结果；
- **enabled 状态随实例保留**：既有的启用/禁用设置不因其它扩展的增删而丢失；
- **移除后再加回 = 全新实例**：移除会彻底丢弃旧实例与全部状态（含 enabled），
  再加回按新实例重新 `initialize()` 一次、enabled 重置为默认启用。
  因此任何时刻同一 id 至多一个实例，不会出现"同 id 两个实例"或禁用状态残留；
- **顺序稳定**：每次调整都对最终集合重跑与启动期完全相同的确定性 `TopologicalSorter`，
  无依赖关系的实现之间始终按声明顺序排列，不因中间动态变动而漂移；动态调整得到的顺序
  与把最终集合做一次纯静态装配的结果逐位一致。

### 5.4 并发可见性

- 注册、动态增减、开关切换由同一把可重入锁串行；状态以不可变快照 + `volatile` 发布；
- 与查询、调用并发时，调用方只能看到某一次**完整装配后**的结果
  （例如不会出现"新扩展已能查到、但它依赖的那个还没就绪"，也不会出现"依赖方还在、
  被依赖方已消失"）；
- 调用器通过同一份快照解析"描述 + 实例"（`getResolvedExtensions`），单次调用视图自洽；
- 校验不通过或初始化失败导致调整失败时，调整前视图继续可用。

---

## 6. 并发安全

- 注册与运行期增减由同一把 `ReentrantLock` 串行化，对外状态保存在不可变快照中、以
  `volatile` 发布；
- 注册、动态增减、开关切换可与查询、调用并发；每次查询/调用只看到某次完整装配的一致视图，
  不出现重复实例、不出现半装配视图；
- 并发 40 线程注册 40 个扩展的场景下，每个扩展恰好一个实例、恰好初始化一次，
  最终顺序与注册到达顺序无关（只由规则决定）。

---

## 7. 配置项

```properties
# extension.enabled.<扩展id>=true|false
# 缺省 = true（启用）；初始化失败的扩展强制禁用
extension.enabled.some-extension=false
```

配置指向不存在的扩展 id 时启动日志会给出明确告警（不会静默忽略）。

---

## 8. 使用示例

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

// 运行期新增一个扩展（原子装配；失败整批回滚、旧视图不变）
registry.add(new FlashDiscount());

// 运行期下线一个扩展（仍被其它扩展依赖时抛 ExtensionInUseException 拒绝）
registry.remove("discount-vip");
```

---

## 9. 可观测性

每次装配（启动注册或运行期增减）后通过 SLF4J 打印装配顺序与判定依据（装配位、id、
扩展点、声明顺序、依赖集合、最终状态、启用位），动态增减另有一条变更摘要日志
（本次 added/removed 与最新装配顺序）；`ExtensionRegistry#getLastAssemblyReport()`
返回结构化的 `AssemblyReport`（含每条 `AssemblyRecord` 的判定原因）。
单测日志同时重定向落盘到 `target/surefire-reports/*-output.txt`。

---

## 10. 与原有静态装配的兼容范围

- 完全向后兼容：原有 `register/registerAll`、`setEnabled`、查询与调用 API 的签名和语义
  均未改变，既有用例（`AssemblyOrderingTest`、`LifecycleAndIsolationTest`、
  `ConcurrentAccessTest`、`SpringIntegrationTest`）零改动通过；
- 动态新增/下线是叠加在注册表上的**编程式 API**，不要求使用方改为 Spring Bean，
  Spring 环境下可直接注入 `ExtensionRegistry` 调用；
- 动态与静态共用同一套校验、拓扑排序、快照与初始化逻辑，因此装配顺序、一次性初始化、
  故障处理规则在两条路径上保持一致；
- 唯一刻意的语义差异：启动期 `registerAll` 对单个扩展初始化失败采用**隔离**（`FAILED`、
  强制禁用、其余继续），运行期 `addAll` 对新扩展初始化失败采用**整批回滚**
  （`DynamicReconfigurationException`，旧视图不变）。

---

## 11. 测试覆盖与本地验证

### 11.1 测试覆盖

| 测试类 | 覆盖场景 |
| --- | --- |
| `AssemblyOrderingTest` | 声明顺序、id 破平、依赖优先、重复标识、悬空依赖、依赖成环（含自环）、失败后无部分装配 |
| `LifecycleAndIsolationTest` | 初始化失败隔离、禁用稳定结果、反复切换不重复初始化/无残留、失败扩展调用语义、未知 id 拒绝 |
| `ConcurrentAccessTest` | 并发注册无重复实例且只初始化一次、并发调用+切换快照一致 |
| `DynamicReconfigurationTest` | 动态新增参与装配并可调用、下线从所有视图彻底消失、依赖保护拒绝（单删/批量）、未知 id 拒绝、非法新增回滚、新扩展初始化失败整批回滚、未受影响扩展不重复初始化且保留 enabled、移除再加回=新实例/状态重置、搅动后顺序与纯静态装配一致、固定脚本连跑两遍一致、并发增减+调用+失败调整只见完整视图 |
| `SpringIntegrationTest` | Bean 收集装配、配置驱动开关、失败隔离、重复 id 启动失败原因可区分 |

### 11.2 本地验证

```bash
# 1. 编译 + 全量单测（推荐连续执行两遍，确认结果稳定可复现）
mvn test
mvn test

# 2. 只跑动态调整专项
mvn test -Dtest=DynamicReconfigurationTest

# 3. 查看含装配顺序与判定依据的测试日志
cat target/surefire-reports/com.github.xingjianluo.extensionregistryjava.extension.DynamicReconfigurationTest-output.txt
```

预期：两轮 `mvn test` 结果一致，共 29 个用例全部通过、0 失败；日志中可观察到每次
动态增减后的装配位、依赖集合、状态与启用位，以及固定脚本两遍完全相同的顺序轨迹。
