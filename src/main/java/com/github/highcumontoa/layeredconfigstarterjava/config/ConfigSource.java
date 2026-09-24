package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.Map;

/**
 * 一个配置来源。加载结果为键值对（均为字符串）。
 */
public interface ConfigSource {

    /** 来源名称，用于日志与错误定位。 */
    String name();

    /**
     * 加载该来源的全部键值。
     *
     * @throws ConfigException 原因 SOURCE_UNAVAILABLE，当来源不可读时
     */
    Map<String, String> load();
}
