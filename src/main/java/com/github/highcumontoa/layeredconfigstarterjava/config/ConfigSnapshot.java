package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.Map;

/**
 * 某个来源的一次加载快照（骨架）。
 * 键为 scope.name 规范名，值为原始字符串。
 */
public record ConfigSnapshot(
        String sourceName,
        int priority,
        Map<String, String> values
) {
}
