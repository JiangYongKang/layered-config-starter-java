package com.github.highcumontoa.layeredconfigstarterjava.config;

/**
 * 单个取值的来源信息（骨架）。
 */
public record ValueOrigin(
        String sourceName,
        int priority,
        String rawValue,
        String location
) {
}
