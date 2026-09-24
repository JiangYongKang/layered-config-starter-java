package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层加载器：按优先级合并多个来源，依据 schema 校验并生成不可变快照。
 *
 * 优先级规则（稳定且明确）：sources 列表中靠后的来源覆盖靠前的来源，
 * 推荐顺序为 默认值 -> 外部文件 -> 环境变量，即环境变量优先级最高。
 *
 * 校验规则：
 * - 来源中出现 schema 未注册的键 -> UNKNOWN_KEY；
 * - 键被限定在特定环境，却在当前环境被来源提供 -> CROSS_ENVIRONMENT_MISUSE；
 * - 必需键缺失 -> MISSING_REQUIRED（不静默降级为默认值）；
 * - 类型不匹配 -> TYPE_MISMATCH；取值非法 -> ILLEGAL_VALUE。
 * 任何失败都不产出部分快照。
 */
public final class LayeredConfigLoader {

    private final ConfigSchema schema;
    private final String environment;

    public LayeredConfigLoader(ConfigSchema schema, String environment) {
        this.schema = schema;
        this.environment = environment;
    }

    public String environment() {
        return environment;
    }

    /**
     * 加载并合并所有来源，返回校验通过的不可变快照。
     */
    public ConfigSnapshot load(List<ConfigSource> sources) {
        // 1. 逐层合并，记录每个键的最终来源（provenance）
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> provenance = new LinkedHashMap<>();
        for (ConfigSource source : sources) {
            Map<String, String> layer = source.load();
            for (Map.Entry<String, String> e : layer.entrySet()) {
                merged.put(e.getKey(), e.getValue());
                provenance.put(e.getKey(), source.name());
            }
        }

        // 2. 未知键检查（一次性报告全部，便于定位）
        List<String> unknownKeys = new ArrayList<>();
        for (String key : merged.keySet()) {
            if (schema.find(key).isEmpty()) {
                unknownKeys.add(key);
            }
        }
        if (!unknownKeys.isEmpty()) {
            throw new ConfigException(ConfigException.Reason.UNKNOWN_KEY, unknownKeys.get(0),
                    "环境 [" + environment + "] 中存在未注册的键 " + unknownKeys
                            + "（来源: " + provenance.get(unknownKeys.get(0)) + "）");
        }

        // 3. 跨环境误用检查：键被限定在特定环境，却被当前环境的来源提供
        for (Map.Entry<String, String> e : merged.entrySet()) {
            ConfigKey def = schema.find(e.getKey()).orElseThrow();
            if (!def.environments().isEmpty() && !def.environments().contains(environment)) {
                throw new ConfigException(ConfigException.Reason.CROSS_ENVIRONMENT_MISUSE, e.getKey(),
                        "键 [" + e.getKey() + "] 仅允许用于环境 " + def.environments()
                                + "，却被环境 [" + environment + "] 的来源 ["
                                + provenance.get(e.getKey()) + "] 提供");
            }
        }

        // 4. 逐键解析：取值 -> 类型解析 -> 取值校验
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> resolvedProvenance = new LinkedHashMap<>();
        for (ConfigKey def : schema.keys().values()) {
            boolean appliesHere = def.environments().isEmpty()
                    || def.environments().contains(environment);
            if (!appliesHere) {
                // 属于其他环境的键不进入本环境快照，保证隔离
                continue;
            }
            String raw = merged.get(def.name());
            if (raw == null) {
                if (def.defaultValue() != null) {
                    values.put(def.name(), def.defaultValue());
                    resolvedProvenance.put(def.name(), "schema-default");
                } else if (def.required()) {
                    throw new ConfigException(ConfigException.Reason.MISSING_REQUIRED, def.name(),
                            "必需键 [" + def.name() + "] 在环境 [" + environment
                                    + "] 的所有来源中均缺失，且未声明默认值");
                }
                continue;
            }
            Object parsed = ValueParser.parse(def.name(), raw, def.type());
            ValueParser.validate(def.name(), parsed, def.rule(), def.ruleDescription());
            values.put(def.name(), parsed);
            resolvedProvenance.put(def.name(), provenance.get(def.name()));
        }

        return new ConfigSnapshot(environment, values, resolvedProvenance);
    }
}
