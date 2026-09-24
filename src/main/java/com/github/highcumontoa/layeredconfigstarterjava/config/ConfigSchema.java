package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 配置键注册表。重复定义会被明确标记（DUPLICATE_DEFINITION），不会静默覆盖。
 */
public final class ConfigSchema {

    private final Map<String, ConfigKey> keys = new LinkedHashMap<>();

    public ConfigSchema register(ConfigKey key) {
        ConfigKey existing = keys.get(key.name());
        if (existing != null) {
            throw new ConfigException(ConfigException.Reason.DUPLICATE_DEFINITION, key.name(),
                    "键 [" + key.name() + "] 被重复定义（作用域 " + existing.scope()
                            + " 与 " + key.scope() + "），请合并或重命名");
        }
        keys.put(key.name(), key);
        return this;
    }

    public Optional<ConfigKey> find(String name) {
        return Optional.ofNullable(keys.get(name));
    }

    public Map<String, ConfigKey> keys() {
        return Map.copyOf(keys);
    }
}
