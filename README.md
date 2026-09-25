# Layered Config Starter（分层配置加载与并发安全热更新）

一套可复用的 Java 配置能力：从多个来源分层加载配置、按稳定优先级合并、
运行期原子热刷新、刷新失败整体回滚，并支持多环境 / 多作用域隔离。
提供编程式 API 与 Spring Boot HTTP 两个入口，二者共享同一套语义。

- JDK：21+（源码目标 21）
- 框架：Spring Boot 3.4.x（Web 仅用于演示入口；核心库不依赖 Spring，可单独使用）

---

## 1. 配置来源与优先级

| 优先级 | 来源 | 实现类 | 命名/位置约定 |
| ------ | ---- | ------ | ------------- |
| 100（最高） | 环境变量 | `EnvironmentVariableConfigSource` | `LAYERED_CONFIG__{ENV}__{SCOPE}__{NAME}` |
| 50 | 外部文件 | `ExternalFileConfigSource` | `${config.dir}/config-{env}.properties` |
| 0（最低） | 内置默认值 | `DefaultsConfigSource` | 由 `ConfigKey.defaultValue` 声明 |

合并规则：

1. 每个键逐层收集候选值，**最高优先级来源的非空值生效**；
2. 生效值非法（类型不匹配、越界、不在白名单）时**直接失败，绝不静默降级为默认值**；
3. 每个键都保留逐层候选 `ValueOrigin(来源名, 优先级, 原始值, 定位)`，
   可通过 `ResolvedConfig.explain(key)` 或 HTTP 接口查看判定依据。

文件键格式：`{env}.{scope}.{name}`，例如 `dev.app.threads=32`。
环境变量段统一转小写、以双下划线分段，例如
`LAYERED_CONFIG__DEV__APP__THREADS=32` → 环境 `dev` 的 `app.threads`。

外部文件不存在时按空来源处理（不报错）；必需项是否缺失由合并阶段统一判定。

### 支持的值类型与校验

- 类型：`STRING / INTEGER / LONG / DOUBLE / BOOLEAN`（布尔仅接受 `true/false`）
- 数值范围：`ConfigKey(min, max)`
- 枚举白名单：`ConfigKey(allowedValues)`
- 必需项：`required=true` 且三层来源都无值时加载失败

---

## 2. 可区分的失败原因

所有配置失败都抛出携带 `ConfigFailureReason` 的 `ConfigException`（HTTP 入口映射为
400 响应体中的 `reason` 字段）：

| reason | 触发条件 |
| ------ | -------- |
| `REQUIRED_MISSING` | 必需项在默认值/文件/环境变量中均缺失 |
| `TYPE_MISMATCH` | 生效值无法解析为声明类型，或布尔值非 true/false |
| `INVALID_VALUE` | 生效值越界、不在白名单、文件不可读等 |
| `UNKNOWN_KEY` | 键未在 schema 声明、环境前缀无法识别、环境变量命名不符约定 |
| `DUPLICATE_KEY` | schema 重复声明、同一文件重复键、环境变量规范化后冲突 |
| `CROSS_ENVIRONMENT` | 在某环境的文件容器中写入其它已知环境前缀的键 |

---

## 3. 刷新与回滚语义（并发安全）

`ConfigManager` 按环境维护一份**不可变的已发布快照** `ResolvedConfig` 与单调版本号：

- **原子发布**：刷新时先在新的局部结构中完成“全部来源加载 + 全量校验”，
  全部通过后才通过 `AtomicReference` 一次性替换；读取永远命中完整旧快照或完整新快照，
  不可能观察到部分生效的中间状态。
- **整体回滚**：构建/校验阶段抛出任何异常都不会触碰已发布状态，
  旧配置原样继续可用（异常向上抛出，原因可区分）。
- **并发合并**：每个环境一把 `ReentrantLock`。同一环境并发 `refresh` 时，
  只有第一个线程真正加载，其余线程立即拿到 `ConfigRefreshResult(refreshed=false, 当前版本)`，
  杜绝重复加载与状态错乱；不同环境的刷新互不阻塞。
- **顺序重复刷新**：串行的多次 `refresh` 各自重载并使版本号 +1。
- `init` 只允许调用一次；未初始化环境调用 `current/refresh` 会得到明确错误。

---

## 4. 多环境与多作用域隔离

- 环境集合在 `ConfigSchema(keys, knownEnvironments)` 中显式声明（如 `dev/test/prod`）；
  各环境的状态、版本、刷新锁相互独立，刷新 dev 不影响 prod。
- 作用域是键名的一部分（`scope.name`），不同作用域下同名键互不干扰
  （如 `app.name` 与 `service.name`）。
- 文件是“环境专属容器”：`config-dev.properties` 中出现 `prod.*` 键 →
  `CROSS_ENVIRONMENT` 明确拒绝。
- 环境变量是进程级扁平空间：加载某环境时，其它已知环境段的变量被**过滤（不加载）**，
  绝不污染当前环境；环境段不属于任何已知环境时按 `UNKNOWN_KEY` 拒绝。
- 以上隔离结论与刷新次数无关：多轮刷新后，各环境仍只持有自己的快照（见隔离测试）。

---

## 5. 使用方式

### 5.1 编程式（不依赖 Spring）

```java
ConfigSchema schema = new ConfigSchema(List.of(
        new ConfigKey("app", "name", ConfigValueType.STRING, true, null, null, null, null),
        new ConfigKey("app", "threads", ConfigValueType.INTEGER, false, "4", null, 1.0, 256.0)),
        Set.of("dev", "prod"));

ConfigManager manager = new ConfigManager(schema, List.of(
        new DefaultsConfigSource(schema),
        new ExternalFileConfigSource(Path.of("config"), schema),
        new EnvironmentVariableConfigSource(schema))); // 读取 System.getenv()

ResolvedConfig config = manager.init("dev");
String name = config.getString("app.name").orElseThrow();
List<ValueOrigin> why = config.explain("app.threads");

ConfigRefreshResult result = manager.refresh("dev"); // refreshed/version
```

### 5.2 Spring Boot 演示入口

- 应用属性：`layered.config.dir`（默认 `config/`）、`layered.config.env`（默认 `dev`）
- HTTP（与编程入口同一 `ConfigManager`）：
  - `GET  /config/{env}` 全量生效配置
  - `GET  /config/{env}/key/{scope}/{name}` 单键值 + 逐层判定依据
  - `POST /config/{env}/refresh[?wait=true]` 触发刷新，返回 `{refreshed, version}`
- 自动装配：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  注册 `LayeredConfigAutoConfiguration`，默认导入演示 Bean（`DemoConfigBeans`），
  复用方可声明同类型 Bean 覆盖。

---

## 6. 验证方法

```bash
mvn test
```

测试清单（共 10 个用例，日志会打印配置输入、来源快照与逐键判定依据）：

| 测试类 | 覆盖点 |
| ------ | ------ |
| `LayeredConfigPriorityTest` | 环境变量 > 文件 > 默认值的冲突覆盖；explain 逐层候选 |
| `LayeredConfigRollbackTest` | 6 种可区分失败原因；高优先级非法不回退默认；失败刷新整体回滚、旧快照引用不变；成功刷新版本递增 |
| `LayeredConfigIsolationTest` | dev/prod 多轮刷新互不污染；作用域同名键隔离；其它环境变量被过滤、未知环境段被拒绝 |
| `LayeredConfigConcurrencyTest` | 门控来源确定性制造并发窗口：8 刷新 + 8 读取下仅加载一次、读取永不见半成品；失败刷新期间读取持续命中旧配置 |
| `ApplicationTests` | Spring 上下文与自动装配可启动 |

端到端冒烟：

```bash
mvn package -DskipTests
java -jar target/layered-config-starter-java-1.0-SNAPSHOT.jar --server.port=18080
curl -s http://localhost:18080/config/dev
curl -s -X POST http://localhost:18080/config/dev/refresh
```

---

## 7. 兼容范围与限制

- JDK 21+；Spring Boot 3.4.x（仅演示/自动装配需要，核心 API 可在非 Spring 工程使用）。
- 外部文件解析支持 `.properties` 常用子集：`=`/`:`/空白分隔、`#`/`!` 注释、
  反斜杠续行与 UTF-8 编码；不支持 `PropertyPlaceholder` 占位符替换与多行值，
  需要这些能力时可自行实现 `ConfigSource` 接入。
- 类型系统为内置五种基础类型；扩展方式同样是提供自定义 `ConfigSource`
  或在 `ConfigValueType` / 校验逻辑中扩展。
