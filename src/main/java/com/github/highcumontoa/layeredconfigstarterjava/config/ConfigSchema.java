package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 配置模式定义：声明合法的键集合与已知环境集合。
 * 模式本身是不可变的；构造时若发现重复键定义将直接失败。
 */
public final class ConfigSchema {

    private final List<ConfigKey> keys;
    private final Map<String, ConfigKey> byCanonical;
    private final Set<String> knownEnvironments;

    public ConfigSchema(List<ConfigKey> keys, Set<String> knownEnvironments) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(knownEnvironments, "knownEnvironments");
        this.keys = List.copyOf(keys);
        this.byCanonical = new HashMap<>();
        for (ConfigKey key : this.keys) {
            ConfigKey previous = this.byCanonical.putIfAbsent(key.canonical(), key);
            if (previous != null) {
                // 模式表中同一个键被声明两次属于明确的“重复定义”
                throw new ConfigException(ConfigFailureReason.DUPLICATE_KEY,
                        "配置键重复定义: " + key.canonical());
            }
        }
        this.knownEnvironments = Collections.unmodifiableSet(new HashSet<>(knownEnvironments));
    }

    public List<ConfigKey> keys() {
        return keys;
    }

    public Set<String> knownEnvironments() {
        return knownEnvironments;
    }

    /** 按规范名（scope.name）查找键定义，未知键返回 null。 */
    public ConfigKey find(String canonicalKey) {
        return byCanonical.get(canonicalKey);
    }

    /** 该环境是否为模式已知环境。 */
    public boolean isKnownEnvironment(String environment) {
        return knownEnvironments.contains(environment);
    }
}
