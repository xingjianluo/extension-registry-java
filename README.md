# 可插拔扩展点装配与运行期开关机制

在 Spring Boot 工程内提供一套扩展点（Extension Point）框架：同一扩展点下可注册多个实现，
框架按**确定且可复现**的规则完成校验、排序、初始化，并支持运行期单独启用/禁用，
同时保证故障隔离与并发安全。在此基础上还支持**运行期动态新增/下线扩展实现**：
不重启即可让新实现按既有依赖与顺序规则参与装配、调用，或把旧实现从扩展点彻底移除，
整个调整以原子快照对外可见，校验或初始化失败时整批回滚、不影响线上视图。

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

调用期间获取一次快照，调用过程中发生注册、动态增删或开关切换不影响本次调用结果。

---

## 5. 运行期动态新增 / 下线（不重启调整）

在已启动的注册表上可继续调整，入口均在 `ExtensionRegistry`：

| 方法 | 语义 |
| --- | --- |
| `add(ext)` / `addAll(list)` | 运行期**原子新增**：整批校验 + 初始化全部成功后才发布 |
| `unregister(id)` / `unregisterAll(ids)` | 运行期**原子下线**：从查询与调用结果中彻底移除 |

### 5.1 新增

- 新扩展按**与启动期完全相同**的一套确定性规则参与装配：依赖优先 → 声明 `order()`
  升序 → `id()` 字典序；因此无论增删过多少次，无依赖关系的实现之间始终按声明顺序排列；
- 新增扩展依赖现有扩展、或同批扩展互相依赖都允许，被依赖者先初始化；
- 与启动期 `registerAll` 的唯一差别是**失败策略**：运行期调整采用整批事务——
  校验失败（重复 id `DuplicateExtensionException`、悬空依赖 `DanglingDependencyException`、
  依赖环 `DependencyCycleException`）或任一新增扩展 `initialize()` 失败
  （`ExtensionInitializationException`）时，**整批不发布**，调整前视图照常可查可调，
  已尝试初始化的实例随失败批次一并丢弃（不进入注册表）。启动期 `registerAll`
  仍保持原有“单扩展故障隔离、其余照常装配”的语义不变。

### 5.2 下线与依赖保护

- 下线后扩展从该扩展点的 `getExtensions` / `getEnabledExtensions` /
  `ExtensionInvoker` 结果中**彻底消失**，`getDescriptor(id)`、`getExtension(id)` 返回
  null/空，不保留半生效状态；
- **依赖保护**：只要仍有“保留下来的”扩展依赖某个待下线扩展，本次下线请求被整体拒绝
  （`ExtensionInUseException`，异常上带 `extensionId()` 与 `dependents()` 依赖方列表），
  不做任何部分下线，依赖关系不会被破坏；需要连依赖方一起移除时，把它们放进
  **同一次 `unregisterAll`**（同批一起移除的扩展之间不再视为“仍在依赖”）；
- 对未注册/已下线 id 下线抛 `ExtensionNotRegisteredException`（是
  `IllegalArgumentException` 子类，与 `setEnabled` 对未知 id 的拒绝类型保持一致）；
  同一下线列表里出现重复 id 抛 `IllegalArgumentException`。

### 5.3 实例与状态语义（已定死、前后一致）

- **未被影响的扩展不重复初始化**：增删批次只初始化真正新增的扩展，其余扩展沿用原实例、
  原 `INITIALIZED/FAILED` 状态，之前用 `setEnabled` 设置过的启用位也原样保留；
- **移除后再加入 = 全新实例**：下线会把扩展实例、初始化结果、启用位全部清除，注册表
  **不缓存已下线实例**。同一 id 再加回来时必须重新提交一个扩展对象，框架按全新扩展
  重新初始化一次、默认启用；不会复用旧对象、不继承执行计数等残留状态，也绝不存在
  “同一标识两个实例”。因此第一次提交时初始化失败的批次被回滚后，需要重新构造对象再提交；
- 新增批次仍在事务内：失败回滚不会触碰任何既有扩展。

### 5.4 并发可见性

- 新增、下线与查询、调用共用同一把锁串行化，结果仍通过不可变快照 `volatile` 原子发布；
- 调用方只能看到“调整前”或“调整后”其中一个**完整视图**，不会看到“新扩展已能查到、
  但依赖它的那个还没就绪”之类的中间态；`ExtensionInvoker` 的一次调用自始至终绑定同一份
  快照（描述与实例一次取齐），调用期间发生的调整不影响本次结果；
- 并发下重复新增同一 id 会以 `DuplicateExtensionException` 拒绝、重复下线会以
  `ExtensionNotRegisteredException` 拒绝，这是正常的竞争失败，不破坏已发布视图。

---

## 6. 并发安全

- 注册、运行期新增/下线由 `ReentrantLock` 串行化，对外状态保存在不可变快照中、以
  `volatile` 发布；
- 注册、动态增删、开关切换可与查询、调用并发；查询结果始终一致，不出现重复实例或
  装配中间视图；
- 并发 40 线程注册 40 个扩展的场景下，每个扩展恰好一个实例、恰好初始化一次，
  最终顺序与注册到达顺序无关（只由规则决定）；
- 并发多线程反复新增/下线同一扩展、同时持续调用的场景下，调用方只观察到完整视图
  （详见 `DynamicAdjustmentTest`）。

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
```

运行期不重启动态新增/下线（新实现同样按依赖与声明顺序参与装配）：

```java
// 运行期新增一个依赖 discount-base 的实现；校验或初始化失败会整批回滚
registry.add(new StdDiscount());           // id=discount-std, order=50, deps=[discount-base]
List<Double> prices2 = ExtensionInvoker.invokeEnabled(registry, "price-discount", order);

// 仍被其它扩展依赖时下线会被拒绝，避免弄坏依赖关系
try {
    registry.unregister("discount-base");
} catch (ExtensionInUseException e) {
    // e.dependents() 给出仍在依赖它的扩展，例如 [discount-vip, discount-std]
}

// 连依赖方一起整批下线则允许；之后查询与调用里彻底没有这两个 id
registry.unregisterAll(List.of("discount-base", "discount-vip", "discount-std"));

// 同一 id 重新加回：必须提交新实例，按全新扩展重新初始化、默认启用，不残留旧状态
registry.add(new StdDiscount());
```

> 与静态装配的兼容范围：启动期 Spring Bean 装配、`register/registerAll`、配置驱动开关、
> 故障隔离等既有行为与异常类型全部保持不变；动态能力只是在同一份注册表上叠加
> `add/addAll` 与 `unregister/unregisterAll`，排序规则、快照模型、一次性初始化承诺完全复用。

---

## 9. 可观测性

每次装配（含每次动态增删后的重新装配）后通过 SLF4J 打印装配顺序与判定依据（装配位、id、
扩展点、声明顺序、依赖集合、最终状态、启用位），动态增删另有一行汇总日志
（新增/移除了哪些 id、未受影响扩展保持原实例/状态/开关）；
`ExtensionRegistry#getLastAssemblyReport()` 返回结构化的 `AssemblyReport`
（含每条 `AssemblyRecord` 的判定原因）。
单测日志同时重定向落盘到 `target/surefire-reports/*-output.txt`。

---

## 10. 测试覆盖与本地验证

### 10.1 测试覆盖

| 测试类 | 覆盖场景 |
| --- | --- |
| `AssemblyOrderingTest` | 声明顺序、id 破平、依赖优先、重复标识、悬空依赖、依赖成环（含自环）、失败后无部分装配 |
| `LifecycleAndIsolationTest` | 初始化失败隔离、禁用稳定结果、反复切换不重复初始化/无残留、失败扩展调用语义、未知 id 拒绝 |
| `ConcurrentAccessTest` | 并发注册无重复实例且只初始化一次、并发调用+切换快照一致 |
| `DynamicAdjustmentTest` | 动态新增参与排序与调用、下线彻底消失、依赖保护拒绝/整批连坐可移除、未知/重复下线拒绝、未受影响扩展不重复初始化且保留开关、移除后再加为新实例、非法图与初始化失败整批回滚旧视图可用、多轮调整顺序确定且连跑两遍一致、并发增删只见完整视图 |
| `SpringIntegrationTest` | Bean 收集装配、配置驱动开关、失败隔离、重复 id 启动失败原因可区分、Spring 容器内运行期增删与静态装配兼容 |

### 10.2 本地怎么验证

```bash
# 编译 + 全量单测（建议连续执行两遍，确认结果稳定、可复现）
mvn test
mvn test

# 只看动态能力相关用例
mvn test -Dtest=DynamicAdjustmentTest
```

- 控制台与 `target/surefire-reports/*-output.txt` 中可看到每个用例的
  “装配位[index] id=... declaredOrder=... dependencies=... state=... enabled=... 判定依据: ...”
  以及“运行期新增完成 / 运行期下线完成”汇总，据此核对装配顺序与判定依据；
- 判定依据规则：被依赖者优先；无约束时 `declaredOrder` 升序；再相同按 id 字典序。
