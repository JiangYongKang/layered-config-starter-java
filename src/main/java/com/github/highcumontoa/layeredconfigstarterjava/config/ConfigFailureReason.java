package com.github.highcumontoa.layeredconfigstarterjava.config;

/**
 * 配置加载失败的可区分原因（骨架）。
 */
public enum ConfigFailureReason {
    REQUIRED_MISSING,
    TYPE_MISMATCH,
    INVALID_VALUE,
    UNKNOWN_KEY,
    DUPLICATE_KEY,
    CROSS_ENVIRONMENT
}
