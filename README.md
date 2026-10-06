# layered-config-starter-java

可复用的分层配置加载与并发安全热更新组件，位于
`com.github.highcumontoa.layeredconfigstarterjava.config` 包下。

## 配置来源与优先级

支持的来源（均实现 `ConfigSource` 接口）：

| 来源 | 实现 | 说明 |
| --- | --- | --- |
| 默认值 | `MapConfigSource` | 应用内置的兜底键值 |
| 外部文件 | `PropertiesFileSource` | properties 文件，文件缺失/不可读时报 `SOURCE_UNAVAILABLE`；同一键写多遍时报 `DUPLICATE_KEY_IN_SOURCE` |
| 环境变量 | `EnvironmentVariableSource` | 只认约定前缀的命名空间，通常优先级最高 |

### 环境变量命名空间映射

`EnvironmentVariableSource` 只识别约定前缀内的变量，**前缀之外的环境变量一律忽略**
（线上机器上存在大量与业务无关的变量是常态，它们既不报错也不会进入配置）。
映射规则（以前缀 `APP_` 为例）：

```
APP_APP_PORT  ->  去前缀 APP_PORT  ->  转小写 app_port  ->  '_' 替换为 '.'  ->  app.port
```

- 每次 `load()` 都重新读取环境变量快照，热刷新可感知运行期注入的变量；
- 前缀不允许为空（空前缀会把整台机器的变量都当成配置键），构造时直接拒绝；
- 测试或非本机场景可通过 `EnvironmentVariableSource(prefix, envSupplier)` 注入变量快照。

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
| `DUPLICATE_KEY_IN_SOURCE` | 同一键在外部 properties 文件中被写了多遍（不静默采用最后一条） |
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

## HTTP 管理接口

`ConfigAdminController` 提供运行期运维接口，与程序内部刷新共用同一个
`ReloadableConfig` Bean，因此接口触发与程序触发看到的是同一份实时配置，
原子生效/整体回滚/串行化语义完全一致，并发调用只会排队执行：

| 接口 | 说明 |
| --- | --- |
| `POST /config/reload` | 手动触发一次刷新。成功返回 200 及新快照；失败返回 409 及 `{success, reason, key, message}`，旧配置继续可用 |
| `GET /config/current` | 查询当前生效值及每个键的来源（`values` + `provenance`） |

Spring 装配由 `LayeredConfigAutoConfiguration` 完成，可用属性：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `layered-config.environment` | `default` | 当前环境名（用于环境隔离判定） |
| `layered-config.file` | 空 | 外部 properties 文件路径，留空则不启用文件来源 |
| `layered-config.env-prefix` | `APP_` | 环境变量命名空间前缀 |

来源顺序固定为：内置默认值 -> 外部文件（可选）-> 环境变量（约定前缀）。

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
- 配置组件（`config` 包）本身不依赖 Spring，可在非 Spring 场景独立使用；
  HTTP 管理接口（`web` 包）与 Spring 装配依赖 spring-boot-starter-web；
- 键值来源为字符串键值对，支持类型：String、int、long、double、boolean、枚举；
- properties 文件解析兼容注释（`#`/`!`）、`=`/`:`/空白三种分隔符与反斜杠续行，
  与 `java.util.Properties` 行为对齐，但重复键会显式报错而非静默覆盖。

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
  多次刷新后隔离结论仍成立；
- `EnvironmentVariableSourceTest`：机器上同时存在大量无关环境变量时仅取约定
  前缀内的变量并正确映射键名、与默认值/文件合并后 env 优先且 provenance 可解释、
  空前缀被拒绝；
- `PropertiesFileSourceTest`：文件中同一键写多遍被识别为
  `DUPLICATE_KEY_IN_SOURCE`（含刷新时回滚）、注释/分隔符/续行的正常解析、
  文件缺失报 `SOURCE_UNAVAILABLE`；
- `ConfigAdminControllerTest`：走 HTTP 接口查询生效值与来源、接口触发刷新成功
  生效、非法值与文件重复键刷新返回 409 且旧值回滚继续可用、8 线程并发调用接口
  后状态一致。

### 本地手动验证 HTTP 接口

```bash
# 1. 准备一个外部配置文件
echo -e "app.name=local-demo\napp.port=9090" > /tmp/app.properties

# 2. 启动应用（可用环境变量覆盖：APP_APP_PORT=7070 会覆盖文件里的端口）
mvn spring-boot:run -Dspring-boot.run.arguments="--layered-config.file=/tmp/app.properties"

# 3. 查询当前生效值与来源
curl -s localhost:8080/config/current

# 4. 修改 /tmp/app.properties 后手动触发刷新
curl -s -X POST localhost:8080/config/reload

# 5. 把文件改成非法值（如 app.port=70000）再刷新，应返回 409 与
#    {"success":false,"reason":"ILLEGAL_VALUE",...}，且 /config/current 仍是旧值
```
