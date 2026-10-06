package com.github.highcumontoa.layeredconfigstarterjava;

import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigKey;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSchema;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.EnvironmentVariableSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.LayeredConfigLoader;
import com.github.highcumontoa.layeredconfigstarterjava.config.MapConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.PropertiesFileSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.ReloadableConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分层配置组件的 Spring 装配。
 *
 * 来源顺序（后者覆盖前者）：内置默认值 -> 外部文件（可选）-> 环境变量（约定前缀）。
 * 程序内部刷新与 HTTP 接口刷新共用同一个 {@link ReloadableConfig} Bean，
 * 因此看到的是同一份实时配置。
 */
@Configuration
public class LayeredConfigAutoConfiguration {

    @Bean
    public ConfigSchema configSchema() {
        return new ConfigSchema()
                .register(ConfigKey.of("app.name", String.class)
                        .defaultValue("layered-config-app").build())
                .register(ConfigKey.of("app.port", Integer.class)
                        .defaultValue(8080)
                        .rule(v -> (Integer) v > 0 && (Integer) v < 65536, "端口须在 1..65535")
                        .build())
                .register(ConfigKey.of("app.debug", Boolean.class)
                        .defaultValue(Boolean.FALSE).build());
    }

    @Bean
    public ReloadableConfig reloadableConfig(
            ConfigSchema configSchema,
            @Value("${layered-config.environment:default}") String environment,
            @Value("${layered-config.file:}") String file,
            @Value("${layered-config.env-prefix:APP_}") String envPrefix) {
        List<ConfigSource> sources = new ArrayList<>();
        sources.add(new MapConfigSource("defaults", Map.of(
                "app.name", "layered-config-app",
                "app.port", "8080",
                "app.debug", "false")));
        if (file != null && !file.isBlank()) {
            sources.add(new PropertiesFileSource(Path.of(file)));
        }
        sources.add(new EnvironmentVariableSource(envPrefix));
        return new ReloadableConfig(new LayeredConfigLoader(configSchema, environment), sources);
    }
}
