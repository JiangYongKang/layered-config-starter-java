package com.github.highcumontoa.layeredconfigstarterjava.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 分层配置能力的自动装配入口。
 *
 * <p>复用方两种用法：
 * <ol>
 *   <li>直接编程式构造：{@code new ConfigManager(schema, sources)}，完全不依赖 Spring；</li>
 *   <li>引入本 starter：当前默认装配一份可运行的演示 Bean（{@link DemoConfigBeans}），
 *       复用方可声明自己的同类型 Bean 覆盖。</li>
 * </ol>
 */
@AutoConfiguration
@Import(DemoConfigBeans.class)
public class LayeredConfigAutoConfiguration {
}
