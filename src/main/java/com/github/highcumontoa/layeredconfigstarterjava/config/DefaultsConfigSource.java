package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 内置默认值来源（优先级 0，最低）。
 * 仅收集 schema 中声明了 defaultValue 的键；默认值本身在合并阶段统一做类型/合法性校验。
 */
public class DefaultsConfigSource implements ConfigSource {

    static final int PRIORITY = 0;

    private final ConfigSchema schema;

    public DefaultsConfigSource(ConfigSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public String name() {
        return "defaults";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public ConfigSnapshot load(String environment) {
        // 默认值与环境无关：所有环境共享同一份内置默认值
        Map<String, String> values = new HashMap<>();
        for (ConfigKey key : schema.keys()) {
            if (key.defaultValue() != null) {
                values.put(key.canonical(), key.defaultValue());
            }
        }
        return new ConfigSnapshot(name(), priority(), Map.copyOf(values));
    }
}
