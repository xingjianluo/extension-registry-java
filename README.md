# extension-registry-java

可插拔的扩展点装配与运行期开关机制：同一扩展点下的多个实现按确定规则被装配、启用与调用。

## 扩展点契约

- **扩展点**：一个继承 `ExtensionPoint` 标记接口的 Java 接口（见 `demo/GreetingExtension`）。
- **扩展实现**：通过 `ExtensionDescriptor` 声明并注册到 `ExtensionRegistry`：
  - `id`：同一扩展点下唯一，非空；
  - `order`：装配次序提示，数值小者优先；
  - `dependsOn`：依赖的其它扩展 `id`，依赖项必先于本扩展装配。
- **实例工厂**：注册时传入 `Supplier`，装配期惰性调用且**每个扩展最多初始化一次**。

### 在 Spring 中贡献扩展

```java
@Bean
ExtensionContribution<GreetingExtension> formalGreeting() {
    return ExtensionContribution.of(GreetingExtension.class, "formal", 10,
            () -> name -> "Good day, " + name + ".");
}
```

`ExtensionConfiguration` 在上下文刷新后统一收集所有 `ExtensionContribution` Bean，
按扩展点分组注册并装配。

### 调用扩展

```java
ExtensionPointView<GreetingExtension> view = registry.view(GreetingExtension.class);
List<String> all = view.invokeAll(g -> g.greet("world")); // 按装配顺序调用所有可用扩展
Optional<String> first = view.invokeFirst(g -> g.greet("world"));
```

## 装配顺序与依赖规则

装配（`ExtensionRegistry#assemble`）按以下规则进行，结果**可复现**（与注册先后、JVM 进程无关）：

1. **依赖优先**：`dependsOn` 声明的扩展必先于依赖者装配（拓扑排序）；
2. **次序提示**：无依赖约束时按 `order` 升序；
3. **标识兜底**：`order` 相同按 `id` 字典序。

违反规则的声明被**明确拒绝**，抛出 `ExtensionAssemblyException`，原因可通过 `reason()` 区分：

| 原因 | 含义 |
|---|---|
| `DUPLICATE_ID` | 同一扩展点下出现重复扩展标识 |
| `DANGLING_DEPENDENCY` | 依赖指向不存在的扩展标识 |
| `CYCLIC_DEPENDENCY` | 依赖关系成环（异常信息含成环路径） |
| `INITIALIZATION_FAILED` | 扩展工厂初始化抛异常（见故障隔离） |

不会静默覆盖重复标识，也不依赖类加载顺序等不确定因素。

### 故障隔离

- 单个扩展初始化失败**不影响其它扩展**：失败记录为 `ExtensionFailure`（`view.failures()`），
  其余扩展正常装配、可用；
- 装配结果通过 volatile **原子发布**，调用方不会观察到装配到一半的状态；
- 注册、装配、查询、启停均可并发执行，查询结果始终一致、无重复实例。

## 启用 / 禁用语义

- 运行期开关：`registry.setEnabled(pointType, extensionId, false)`；
- 禁用后经由该扩展点的调用（`active()` / `invokeAll()` / `invokeFirst()`）**稳定地跳过**该扩展，
  结果可预期；全部禁用时 `invokeAll` 返回空列表、`invokeFirst` 返回 `Optional.empty()`；
- 开关只翻转标志位：**不触发重复初始化、无状态残留、无未声明的惰性副作用**，
  反复切换后重新启用即恢复原实例；
- 对未装配的扩展点或未知扩展标识操作会抛出异常，而非静默忽略。

## 配置项

| 配置 | 说明 |
|---|---|
| `extension.disabled` | 启动时即禁用的扩展列表，格式 `<扩展点全限定类名>#<扩展标识>` |

示例：

```properties
extension.disabled=com.github.xingjianluo.extensionregistryjava.demo.GreetingExtension#cheerful
```

无法解析或指向未知扩展的条目会记录警告并忽略，不影响启动。

## 演示

`demo` 包提供了 `GreetingExtension` 扩展点与三个实现（`casual` 依赖 `formal`），
启动应用后：

```bash
curl 'http://localhost:8080/api/greetings?name=world'
# {"assembledOrder":["formal","casual","cheerful"],
#  "greetings":["Good day, world.","Hi world!","Hey world, great to see you!"]}
```

## 测试

```bash
mvn test
```

`ExtensionRegistryTest` 覆盖重复标识、悬空依赖、依赖成环、初始化失败隔离、
启停语义与并发访问；单测日志会打印装配顺序与判定依据。
`ExtensionAssemblySpringTest` 覆盖 Spring 装配与配置驱动禁用。
