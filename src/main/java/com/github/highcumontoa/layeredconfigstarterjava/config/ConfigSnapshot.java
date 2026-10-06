package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.Map;

/**
 * 不可变的已解析配置快照。读取只针对快照，保证刷新期间不会观察到中间状态。
 */
public final class ConfigSnapshot {

    private final String environment;
    private final Map<String, Object> values;
    private final Map<String, String> provenance;

    public ConfigSnapshot(String environment, Map<String, Object> values, Map<String, String> provenance) {
        this.environment = environment;
        this.values = Map.copyOf(values);
        this.provenance = Map.copyOf(provenance);
    }

    public String environment() { return environment; }

    public Object get(String key) {
        Object value = values.get(key);
        if (value == null && !values.containsKey(key)) {
            throw new ConfigException(ConfigException.Reason.UNKNOWN_KEY, key,
                    "键 [" + key + "] 不在当前环境 [" + environment + "] 的已解析配置中");
        }
        return value;
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public String getString(String key) { return (String) get(key); }
    public int getInt(String key) { return (Integer) get(key); }
    public long getLong(String key) { return (Long) get(key); }
    public boolean getBoolean(String key) { return (Boolean) get(key); }
    public double getDouble(String key) { return (Double) get(key); }

    /** 该键最终生效值来自哪个来源（用于可解释性）。 */
    public String provenance(String key) {
        return provenance.get(key);
    }

    public Map<String, Object> asMap() {
        return values;
    }

    /** 全部键的来源映射（用于可解释性输出）。 */
    public Map<String, String> provenanceMap() {
        return provenance;
    }
}
