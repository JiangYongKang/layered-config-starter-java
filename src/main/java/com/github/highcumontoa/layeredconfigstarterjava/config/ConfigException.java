package com.github.highcumontoa.layeredconfigstarterjava.config;

/**
 * 配置加载/刷新失败时抛出的异常，携带可区分的失败原因。
 */
public class ConfigException extends RuntimeException {

    /** 可区分的失败原因。 */
    public enum Reason {
        MISSING_REQUIRED,
        TYPE_MISMATCH,
        ILLEGAL_VALUE,
        UNKNOWN_KEY,
        DUPLICATE_DEFINITION,
        DUPLICATE_KEY_IN_SOURCE,
        CROSS_ENVIRONMENT_MISUSE,
        SOURCE_UNAVAILABLE
    }

    private final Reason reason;
    private final String key;

    public ConfigException(Reason reason, String key, String message) {
        super(message);
        this.reason = reason;
        this.key = key;
    }

    public Reason reason() {
        return reason;
    }

    public String key() {
        return key;
    }
}
