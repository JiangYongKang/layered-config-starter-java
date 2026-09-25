package com.github.highcumontoa.layeredconfigstarterjava.config;

/**
 * 配置来源（骨架）。
 */
public interface ConfigSource {

    /** 来源名称（用于解释取值依据）。 */
    String name();

    /** 数字越大优先级越高。 */
    int priority();

    /**
     * 为指定环境加载一次快照。
     *
     * @param environment 环境标识（如 dev/prod）
     */
    ConfigSnapshot load(String environment);
}
