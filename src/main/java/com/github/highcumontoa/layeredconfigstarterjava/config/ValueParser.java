package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.function.Predicate;

/**
 * 字符串到目标类型的解析器。
 * 解析失败抛出 TYPE_MISMATCH；取值校验失败抛出 ILLEGAL_VALUE。
 * 绝不静默降级为默认值。
 */
public final class ValueParser {

    private ValueParser() {
    }

    public static Object parse(String key, String raw, Class<?> type) {
        if (raw == null) {
            throw new ConfigException(ConfigException.Reason.TYPE_MISMATCH, key,
                    "键 [" + key + "] 的原始值为 null，无法解析为 " + type.getSimpleName());
        }
        String trimmed = raw.trim();
        try {
            if (type == String.class) {
                return trimmed;
            }
            if (type == Integer.class || type == int.class) {
                return Integer.valueOf(trimmed);
            }
            if (type == Long.class || type == long.class) {
                return Long.valueOf(trimmed);
            }
            if (type == Double.class || type == double.class) {
                return Double.valueOf(trimmed);
            }
            if (type == Boolean.class || type == boolean.class) {
                if ("true".equalsIgnoreCase(trimmed)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(trimmed)) {
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("不是合法布尔值: " + trimmed);
            }
            if (Enum.class.isAssignableFrom(type)) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<? extends Enum>) type, trimmed);
                return enumValue;
            }
        } catch (IllegalArgumentException e) {
            throw new ConfigException(ConfigException.Reason.TYPE_MISMATCH, key,
                    "键 [" + key + "] 的值 [" + trimmed + "] 无法解析为 "
                            + type.getSimpleName() + ": " + e.getMessage());
        }
        throw new ConfigException(ConfigException.Reason.TYPE_MISMATCH, key,
                "键 [" + key + "] 声明了不支持的类型: " + type.getName());
    }

    public static void validate(String key, Object value, Predicate<Object> rule, String ruleDescription) {
        if (rule != null && !rule.test(value)) {
            throw new ConfigException(ConfigException.Reason.ILLEGAL_VALUE, key,
                    "键 [" + key + "] 的值 [" + value + "] 违反取值约束: " + ruleDescription);
        }
    }
}
