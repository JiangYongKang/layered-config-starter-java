# layered-config-starter-java

可复用的分层配置加载与并发安全热更新组件，位于
`com.github.highcumontoa.layeredconfigstarterjava.config` 包下。

## 本次新增能力

1. **系统环境变量来源（命名空间过滤）**：`EnvironmentVariableSource`
   直接从运行机器的环境变量取值，但只认本应用约定前缀（如 `MYAPP_`）内的变量，
   机器上其它与业务无关的环境变量一律忽略；
2. **配置文件重复键识别**：外部 properties 文件中同一键书写两次或更多次时，
   明确报 `DUPLICATE_DEFINITION`（含两次出现的行号），不再静默使用最后一个值；
3. **运行期 HTTP 管理接口**：`GET /config` 查询当前生效值及最终来源，
   `POST /config/reload` 手动触发刷新；接口刷新与程序内部刷新共用同一实例，
   失败时区分原因返回并整体回滚，并发调用由组件串行化保护。

## 配置来源与优先级

支持的来源（均实现 `ConfigSource` 接口）：

| 来源 | 实现 | 说明 |
| --- | --- | --- |
| 默认值 | `MapConfigSource` | 应用内置的兜底键值 |
| 外部文件 | `PropertiesFileSource` | properties 文件；缺失/不可读报 `SOURCE_UNAVAILABLE`，同键重复报 `DUPLICATE_DEFINITION` |
| 环境变量 | `EnvironmentVariableSource` | 只读命名空间前缀内的系统环境变量，通常优先级最高 |

**优先级规则（明确且稳定）**：调用 `LayeredConfigLoader.load(sources)` 时，
列表中**靠后的来源覆盖靠前的来源**。推荐顺序：

```
默认值 -> 外部文件 -> 环境变量        （环境变量优先级最高）
```

每个键最终生效值的来源可通过 `ConfigSnapshot.provenance(key)` 查询，
用于解释"这个值为什么是这个值"。环境变量取到的是普通字符串键值对，
与默认值、外部文件走完全相同的合并、类型解析、取值校验与 provenance 记录流程，
因此最终生效值与来源在三种来源之间行为完全一致。

### 环境变量命名空间约定

线上机器通常带有大量与业务无关的环境变量（`PATH`、`HOME`、`LANG`……）。
`EnvironmentVariableSource("MYAPP_")` 只读取以该前缀开头的变量，
其余变量**既不会报错，也不会被当成非法键**：

```
环境变量名              去掉前缀后转换               配置键
MYAPP_APP_PORT    -->   APP_PORT --> app.port   -->  app.port
MYAPP_APP_DEBUG   -->   APP_DEBUG --> app.debug -->  app.debug
PATH / HOME / ...   （前缀不匹配）                    忽略
```

转换规则：去掉前缀 → 整体转小写 → 下划线 `_` 替换为点 `.`。
注意：命名空间内、但映射后未在 schema 注册的键仍会按 `UNKNOWN_KEY` 失败
（属于"本应用自己的变量写错了"，与应忽略的系统变量区分开）。

## HTTP 管理接口

由 `web.ConfigAdminController` 提供（Spring MVC，需应用是 web 应用）：

| 接口 | 说明 |
| --- | --- |
| `GET /config` | 返回当前生效的全部键，每项含 `key`、`value`、`provenance`（最终来源）与 `environment` |
| `GET /config?key=app.port` | 只查单个键；键不在当前环境快照时返回 `404` 且 `reason=UNKNOWN_KEY` |
| `POST /config/reload` | 手动触发一次刷新。成功 `200`；失败 `409`，响应体含可区分的 `reason`、`key`、`message` 与 `rollback` 说明 |

- 接口调用的 `ReloadableConfig` 与程序内部是**同一个 Bean / 同一把锁**，
  因此手动刷新拿到的就是实时配置，接口与内部触发不会出现两份状态；
- 刷新成功后通过不可变快照原子切换；失败时整体回滚，
  `GET /config` 与程序读取仍返回上一次成功加载的结果，不会生效到一半；
- 同时多次调用 `POST /config/reload` 会排队串行执行，内部状态不会被搞乱。

成功响应示例：

```json
{"success": true, "environment": "dev", "detail": "刷新成功，3 个键已生效",
 "reason": null, "key": null, "message": null, "rollback": null}
```

失败响应示例（HTTP 409）：

```json
{"success": false, "environment": "dev", "detail": null,
 "reason": "ILLEGAL_VALUE", "key": "app.port",
 "message": "键 [app.port] 的值 [70000] 违反取值约束: 端口须在 1..65535",
 "rollback": "刷新失败，已整体回滚，当前生效配置仍为上一次成功加载的结果"}
```

## Spring 装配

`DemoLayeredConfigConfiguration` 给出了把组件注册为 Bean 的示例，可用应用配置调整：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `config.environment` | `dev` | 当前运行环境 |
| `config.file-path` | 空 | 外部 properties 文件路径；不配置则不使用文件来源 |
| `config.env-prefix` | `MYAPP_` | 环境变量命名空间前缀 |

## 失败语义（不静默降级）

所有失败以 `ConfigException` 抛出，携带可区分的 `Reason`：

| Reason | 触发条件 |
| --- | --- |
| `MISSING_REQUIRED` | 必需键在所有来源中缺失且未声明默认值 |
| `TYPE_MISMATCH` | 原始值无法解析为声明类型（String/int/long/double/boolean/enum） |
| `ILLEGAL_VALUE` | 值违反 `ConfigKey.rule` 声明的取值约束 |
| `UNKNOWN_KEY` | 来源中出现 schema 未注册的键（含命名空间内映射出的未知键） |
| `DUPLICATE_DEFINITION` | 同一键在 schema 中被重复注册，**或在同一个 properties 文件中被书写多次** |
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

- JDK 21+（构建目标 21），Spring Boot 3.4.x（已在 JDK 25 上构建验证）；
- 配置核心（`config` 包）不依赖 Spring，可在非 Spring 场景独立使用；
  HTTP 管理接口（`web` 包）为可选的 Spring MVC 能力；
- 键值来源为字符串键值对，支持类型：String、int、long、double、boolean、枚举；
- 新增的环境变量来源与重复键检测不改变既有来源接口与合并/校验流程，
  原有直接使用 `PropertiesFileSource` / `MapConfigSource` 的代码无需修改。

## 本地验证

```bash
# 运行全部单测（日志会打印每个用例的配置输入与判定依据）
mvn test

# 只看本次新增场景
mvn test -Dtest='EnvironmentVariableSourceTest,PropertiesFileDuplicateTest,ConfigAdminControllerTest'
```

新增测试覆盖：

- `EnvironmentVariableSourceTest`：
  - 机器上同时存在 9 个无关环境变量（`PATH`/`HOME`/`JAVA_HOME`/其它应用前缀等）
    时，只取 `MYAPP_` 前缀变量并正确映射为配置键；
  - 环境变量与默认值、外部文件合并时优先级最高，provenance 标记为 `env`；
  - 命名空间内、schema 外的键仍按 `UNKNOWN_KEY` 失败（与被忽略的系统变量相区别）。
- `PropertiesFileDuplicateTest`：同一键在文件中写两遍/多遍时
  抛 `DUPLICATE_DEFINITION` 且带两次行号；经加载器整体失败不产快照；
  无重复文件的注释、冒号分隔、续行、转义仍正常解析。
- `ConfigAdminControllerTest`（真实随机端口起服务）：
  - `GET /config` / `?key=` 返回生效值与来源，未知键 404；
  - `POST /config/reload` 成功后接口与内部快照同时生效；
  - 刷新失败返回 409 + 可区分 `reason`，旧值经接口与内部读取均继续可用；
  - 10 线程 × 30 次并发刷新（合法/非法值交替）下 200/409 计数符合预期、坏状态为 0。

手工验证 HTTP 接口：

```bash
mvn spring-boot:run
# 带命名空间环境变量启动，观察环境变量覆盖默认值
MYAPP_APP_PORT=7070 mvn spring-boot:run

curl -s http://localhost:8080/config | jq .
curl -s 'http://localhost:8080/config?key=app.port' | jq .
curl -s -X POST http://localhost:8080/config/reload | jq .
```

## 既有测试（回归全绿）

- `LayeredConfigLoaderTest`：优先级冲突（env > file > defaults）、
  必需项缺失/类型不匹配/取值非法/未知键/重复定义/跨环境误用的可区分失败原因、
  环境间键隔离；
- `ReloadableConfigTest`：非法配置刷新回滚且旧快照继续可用、
  4 刷新线程 × 8 读取线程并发下零中间状态、重复刷新串行且不重复加载、
  多次刷新后隔离结论仍成立。
