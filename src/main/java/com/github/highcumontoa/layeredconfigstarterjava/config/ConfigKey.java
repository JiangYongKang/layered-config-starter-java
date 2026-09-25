package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.Objects;
import java.util.Set;

/**
 * 配置键定义。
 *
 * @param scope         作用域（如 app / datasource），不得为空
 * @param name          作用域内的键名，不得为空
 * @param type          值类型
 * @param required      是否为必需项
 * @param defaultValue  内置默认值（原始字符串，无则为 null）；required=true 时通常为 null
 * @param allowedValues 可选的合法值白名单（为 null 表示不做白名单校验）
 * @param min           数值下界（可空，仅数值类型生效）
 * @param max           数值上界（可空，仅数值类型生效）
 */
public record ConfigKey(
        String scope,
        String name,
        ConfigValueType type,
        boolean required,
        String defaultValue,
        Set<String> allowedValues,
        Double min,
        Double max
) {
    public ConfigKey {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (scope.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("scope 与 name 不得为空白: " + scope + "." + name);
        }
        // 复制为不可变集合，避免外部修改
        allowedValues = (allowedValues == null) ? null : Set.copyOf(allowedValues);
    }

    /** 规范化键：scope.name */
    public String canonical() {
        return scope + "." + name;
    }
}
