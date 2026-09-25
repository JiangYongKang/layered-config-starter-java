package com.github.highcumontoa.layeredconfigstarterjava.config;

/**
 * 配置加载/校验失败异常（骨架）。
 */
public class ConfigException extends RuntimeException {

    private final ConfigFailureReason reason;

    public ConfigException(ConfigFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ConfigException(ConfigFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ConfigFailureReason getReason() {
        return reason;
    }
}
