# layered-config-starter-java

可复用的分层配置加载与并发安全热更新组件，位于
`com.github.highcumontoa.layeredconfigstarterjava.config` 包下。

## 配置来源与优先级

支持的来源（均实现 `ConfigSource` 接口）：

| 来源 | 实现 | 说明 |
| --- | --- | --- |
| 默认值 | `MapConfigSource` | 应用内置的兜底键值 |
| 外部文件 | `PropertiesFileSource` | properties 文件，文件缺失/不可读时报 `SOURCE_UNAVAILABLE` |
| 环境变量 | `MapConfigSource`（包装 `System.getenv()` 快照） | 通常优先级最高 |

**优先级规则（明确且稳定）**：调用 `LayeredConfigLoader.load(sources)` 时，
列表中**靠后的来源覆盖靠前的来源**。推荐顺序：

```
默认值 -> 外部文件 -> 环境变量        （环境变量优先级最高）
```

每个键最终生效值的来源可通过 `ConfigSnapshot.provenance(key)` 查询，
用于解释"这个值为什么是这个值"。

## 失败语义（不静默降级）

所有失败以 `ConfigException` 抛出，携带可区分的 `Reason`：

| Reason | 触发条件 |
| --- | --- |
| `MISSING_REQUIRED` | 必需键在所有来源中缺失且未声明默认值 |
| `TYPE_MISMATCH` | 原始值无法解析为声明类型（String/int/long/double/boolean/enum） |
| `ILLEGAL_VALUE` | 值违反 `ConfigKey.rule` 声明的取值约束 |
| `UNKNOWN_KEY` | 来源中出现 schema 未注册的键 |
| `DUPLICATE_DEFINITION` | 同一键在 schema 中被重复注册 |
| `CROSS_ENVIRONMENT_MISUSE` | 限定特定环境的键被其他环境的来源提供 |
| `SOURCE_UNAVAILABLE` | 文件等来源不可读，或刷新中出现未预期错误 |

任何失败都不会产出部分快照，也不会回退到默认值。

## 刷新与回滚语义

`ReloadableConfig` 提供运行期热刷新：

- **原子生效**：读取走 `snapshot()` 返回的不可变 `ConfigSnapshot`，
  新快照在校验全部通过后通过 `AtomicReference` 一次性切换，
  并发读取永远不会观察到部分生效的中间状态。
- **整体回滚**：`reload()` 失败时返回 `ReloadResult.failed(reason)`，
  旧快照原样保留、继续可用；修复来源后再次 `reload()` 即可生效。
- **串行化**：并发或重复触发的 `reload()` 通过内部锁排队执行，
  同一时刻只有一次加载，不会重复加载或状态错乱。

## 隔离规则

- **环境隔离**：`ConfigKey.environments(...)` 限定键的可用环境。
  属于其他环境的键不会进入本环境快照；本环境来源若提供了其他环境的键，
  报 `CROSS_ENVIRONMENT_MISUSE`。该结论在任意多次刷新后仍然成立
  （每次刷新都重新执行全量校验）。
- **作用域**：`ConfigKey.scope(...)` 为键标注作用域（默认 `global`），
  重复定义检测会带上作用域信息；不同环境/作用域应使用各自的
  `ConfigSchema` + `ReloadableConfig` 实例，互不共享状态。

## 兼容范围

- JDK 21+（构建目标 21），Spring Boot 3.4.x；
- 配置组件本身不依赖 Spring，可在非 Spring 场景独立使用；
- 键值来源为字符串键值对，支持类型：String、int、long、double、boolean、枚举。

## 验证方法

```bash
mvn test
```

测试覆盖（日志中会打印每个用例的配置输入与判定依据）：

- `LayeredConfigLoaderTest`：优先级冲突（env > file > defaults）、
  必需项缺失/类型不匹配/取值非法/未知键/重复定义/跨环境误用的可区分失败原因、
  环境间键隔离；
- `ReloadableConfigTest`：非法配置刷新回滚且旧快照继续可用、
  4 刷新线程 × 8 读取线程并发下零中间状态、重复刷新串行且不重复加载、
  多次刷新后隔离结论仍成立。
