package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 示例装配：声明 schema、三类来源并构建 {@link ConfigManager}，启动时初始化指定环境。
 *
 * <p>可通过应用属性调整：
 * <ul>
 *   <li>{@code layered.config.dir}      外部配置目录，默认 {@code ./config}</li>
 *   <li>{@code layered.config.env}      启动初始化环境，默认 {@code dev}</li>
 * </ul>
 */
@Configuration
public class DemoConfigBeans {

    private static final Logger log = LoggerFactory.getLogger(DemoConfigBeans.class);

    /** 演示用配置模式：多作用域、跨环境共用声明。 */
    @Bean
    public ConfigSchema demoConfigSchema() {
        return new ConfigSchema(
                List.of(
                        new ConfigKey("app", "name", ConfigValueType.STRING, true, null, null, null, null),
                        new ConfigKey("app", "threads", ConfigValueType.INTEGER, false, "4", null, 1.0, 256.0),
                        new ConfigKey("app", "mode", ConfigValueType.STRING, false, "safe",
                                Set.of("safe", "fast"), null, null),
                        new ConfigKey("datasource", "url", ConfigValueType.STRING, true, null, null, null, null),
                        new ConfigKey("datasource", "port", ConfigValueType.INTEGER, false, "5432",
                                null, 1.0, 65535.0),
                        new ConfigKey("feature", "cache", ConfigValueType.BOOLEAN, false, "true", null, null, null)
                ),
                Set.of("dev", "test", "prod"));
    }

    @Bean
    public ConfigManager configManager(ConfigSchema demoConfigSchema,
                                       @Value("${layered.config.dir:config}") String configDir) {
        return new ConfigManager(demoConfigSchema, List.of(
                new DefaultsConfigSource(demoConfigSchema),
                new ExternalFileConfigSource(Path.of(configDir), demoConfigSchema),
                new EnvironmentVariableConfigSource(demoConfigSchema)));
    }

    /** 启动时初始化默认环境；配置非法时应用启动失败（不静默降级）。 */
    @Bean
    public ApplicationRunner configInitRunner(ConfigManager configManager,
                                              @Value("${layered.config.env:dev}") String environment) {
        return (ApplicationArguments args) -> {
            log.info("[启动] 初始化配置环境: {}", environment);
            configManager.init(environment);
        };
    }
}
