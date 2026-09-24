package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 基于 Map 的配置来源，可用于默认值与环境变量快照。
 */
public class MapConfigSource implements ConfigSource {

    private final String name;
    private final Supplier<Map<String, String>> supplier;

    public MapConfigSource(String name, Map<String, String> values) {
        this(name, () -> values);
    }

    public MapConfigSource(String name, Supplier<Map<String, String>> supplier) {
        this.name = name;
        this.supplier = supplier;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<String, String> load() {
        return Map.copyOf(supplier.get());
    }
}
