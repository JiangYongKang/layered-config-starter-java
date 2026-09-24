package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 配置键定义：名称、类型、是否必需、默认值、取值校验规则、允许的环境与作用域。
 */
public final class ConfigKey {

    private final String name;
    private final Class<?> type;
    private final boolean required;
    private final Object defaultValue;
    private final Predicate<Object> rule;
    private final String ruleDescription;
    private final Set<String> environments;
    private final String scope;

    private ConfigKey(Builder b) {
        this.name = b.name;
        this.type = b.type;
        this.required = b.required;
        this.defaultValue = b.defaultValue;
        this.rule = b.rule;
        this.ruleDescription = b.ruleDescription;
        this.environments = b.environments == null ? Set.of() : Set.copyOf(b.environments);
        this.scope = b.scope == null ? "global" : b.scope;
    }

    public String name() { return name; }
    public Class<?> type() { return type; }
    public boolean required() { return required; }
    public Object defaultValue() { return defaultValue; }
    public Predicate<Object> rule() { return rule; }
    public String ruleDescription() { return ruleDescription; }
    public Set<String> environments() { return environments; }
    public String scope() { return scope; }

    public static Builder of(String name, Class<?> type) {
        return new Builder(name, type);
    }

    public static final class Builder {
        private final String name;
        private final Class<?> type;
        private boolean required;
        private Object defaultValue;
        private Predicate<Object> rule;
        private String ruleDescription;
        private Set<String> environments;
        private String scope;

        private Builder(String name, Class<?> type) {
            this.name = name;
            this.type = type;
        }

        public Builder required() { this.required = true; return this; }
        public Builder defaultValue(Object v) { this.defaultValue = v; return this; }
        public Builder rule(Predicate<Object> r, String desc) { this.rule = r; this.ruleDescription = desc; return this; }
        public Builder environments(String... envs) { this.environments = Set.of(envs); return this; }
        public Builder scope(String s) { this.scope = s; return this; }
        public ConfigKey build() { return new ConfigKey(this); }
    }
}
