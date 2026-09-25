package com.github.highcumontoa.layeredconfigstarterjava.config;

/**
 * 刷新结果（骨架）。
 *
 * @param refreshed 是否真正执行了重新加载（并发/重复触发时可能为 false）
 * @param version   当前配置版本
 */
public record ConfigRefreshResult(boolean refreshed, long version) {
}
