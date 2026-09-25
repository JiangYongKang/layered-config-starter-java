package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 环境变量来源（优先级 100，最高）。
 *
 * <p>命名约定：{@code LAYERED_CONFIG__{ENV}__{SCOPE}__{NAME}}，全部大写，段间以双下划线分隔；
 * 解析时环境/作用域/键名段统一转小写。例如：
 * {@code LAYERED_CONFIG__DEV__APP__NAME=demo} → 环境 dev 的 app.name。
 *
 * <p>校验规则：环境段是已知的“其它”环境时直接过滤（属于另一环境，绝不污染当前环境，
 * 因为环境变量是全局扁平空间，与“dev 文件里写 prod. 键”的容器内误用不同）；
 * 环境段无法识别 → UNKNOWN_KEY；键不在 schema → UNKNOWN_KEY；
 * 不同原始名规范化后冲突 → DUPLICATE_KEY。
 */
public class EnvironmentVariableConfigSource implements ConfigSource {

    static final int PRIORITY = 100;
    static final String PREFIX = "LAYERED_CONFIG__";

    private final Map<String, String> env;
    private final ConfigSchema schema;

    public EnvironmentVariableConfigSource(ConfigSchema schema) {
        this(System.getenv(), schema);
    }

    public EnvironmentVariableConfigSource(Map<String, String> env, ConfigSchema schema) {
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public String name() {
        return "environment-variable";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public ConfigSnapshot load(String environment) {
        Objects.requireNonNull(environment, "environment");
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String varName = e.getKey();
            if (!varName.startsWith(PREFIX)) {
                continue;
            }
            String body = varName.substring(PREFIX.length());
            String[] parts = body.split("__", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new ConfigException(ConfigFailureReason.UNKNOWN_KEY,
                        "环境变量命名不符合 LAYERED_CONFIG__{ENV}__{SCOPE}__{NAME} 约定: " + varName);
            }
            String envSeg = parts[0].toLowerCase();
            String canonical = parts[1].toLowerCase() + "." + parts[2].toLowerCase();

            if (schema.knownEnvironments().contains(envSeg) && !envSeg.equals(environment)) {
                // 其它已知环境的变量：忽略，绝不进入当前环境
                continue;
            }
            if (!envSeg.equals(environment)) {
                throw new ConfigException(ConfigFailureReason.UNKNOWN_KEY,
                        "环境变量环境段无法识别: " + varName);
            }
            if (schema.find(canonical) == null) {
                throw new ConfigException(ConfigFailureReason.UNKNOWN_KEY,
                        "未知配置键的环境变量: " + varName + " -> " + canonical);
            }
            String previous = values.putIfAbsent(canonical, e.getValue());
            if (previous != null) {
                throw new ConfigException(ConfigFailureReason.DUPLICATE_KEY,
                        "环境变量规范化后键冲突: " + varName + " -> " + canonical);
            }
        }
        return new ConfigSnapshot(name(), priority(), Map.copyOf(values));
    }
}
