package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次合并并校验通过后的不可变配置。
 *
 * <p>所有 getter 返回的视图均不可变；读取已解析配置不会抛“部分生效”相关异常，
 * 因为非法配置在发布前已被整体拒绝。
 */
public final class ResolvedConfig {

    private final String environment;
    private final ConfigSchema schema;
    private final Map<String, String> finalValues;
    private final Map<String, List<ValueOrigin>> explanations;

    public ResolvedConfig(String environment,
                          ConfigSchema schema,
                          Map<String, String> finalValues,
                          Map<String, List<ValueOrigin>> explanations) {
        this.environment = Objects.requireNonNull(environment);
        this.schema = Objects.requireNonNull(schema);
        Map<String, String> v = new LinkedHashMap<>(Objects.requireNonNull(finalValues));
        Map<String, List<ValueOrigin>> e = new LinkedHashMap<>();
        Objects.requireNonNull(explanations).forEach((k, list) ->
                e.put(k, List.copyOf(list)));
        this.finalValues = Collections.unmodifiableMap(v);
        this.explanations = Collections.unmodifiableMap(e);
    }

    public String environment() {
        return environment;
    }

    /** 原始字符串形式的最终值；未声明的键返回 empty（不做静默回退，只表示无值）。 */
    public Optional<String> getString(String canonicalKey) {
        requireKnown(canonicalKey);
        return Optional.ofNullable(finalValues.get(canonicalKey));
    }

    public Integer getInteger(String canonicalKey) {
        String raw = requirePresent(canonicalKey);
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(ConfigFailureReason.TYPE_MISMATCH,
                    "类型不匹配: key=" + canonicalKey + ", 期望=integer, 实际=" + raw, e);
        }
    }

    public Long getLong(String canonicalKey) {
        String raw = requirePresent(canonicalKey);
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(ConfigFailureReason.TYPE_MISMATCH,
                    "类型不匹配: key=" + canonicalKey + ", 期望=long, 实际=" + raw, e);
        }
    }

    public Double getDouble(String canonicalKey) {
        String raw = requirePresent(canonicalKey);
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(ConfigFailureReason.TYPE_MISMATCH,
                    "类型不匹配: key=" + canonicalKey + ", 期望=double, 实际=" + raw, e);
        }
    }

    public Boolean getBoolean(String canonicalKey) {
        String raw = requirePresent(canonicalKey);
        String b = raw.trim().toLowerCase();
        if (b.equals("true") || b.equals("false")) {
            return Boolean.valueOf(b);
        }
        throw new ConfigException(ConfigFailureReason.TYPE_MISMATCH,
                "类型不匹配: key=" + canonicalKey + ", 期望=boolean, 实际=" + raw);
    }

    /** 某键在各来源的逐层取值依据，按优先级从低到高排列，末位即生效来源。 */
    public List<ValueOrigin> explain(String canonicalKey) {
        requireKnown(canonicalKey);
        return explanations.getOrDefault(canonicalKey, List.of());
    }

    /** 全部最终取值（规范键 -> 字符串值），不可变。 */
    public Map<String, String> asMap() {
        return finalValues;
    }

    private String requirePresent(String canonicalKey) {
        requireKnown(canonicalKey);
        String v = finalValues.get(canonicalKey);
        if (v == null) {
            // 到达这里说明该键既无外部值也无默认值；若是必需项应在发布前已失败
            throw new ConfigException(ConfigFailureReason.REQUIRED_MISSING,
                    "配置项无值: key=" + canonicalKey + ", env=" + environment);
        }
        return v;
    }

    private void requireKnown(String canonicalKey) {
        if (schema.find(canonicalKey) == null) {
            throw new ConfigException(ConfigFailureReason.UNKNOWN_KEY,
                    "读取未知配置键: " + canonicalKey);
        }
    }
}
