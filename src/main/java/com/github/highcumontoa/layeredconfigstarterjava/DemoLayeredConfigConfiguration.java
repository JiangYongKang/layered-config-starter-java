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
 * 应用侧装配示例：把组件的分层加载器、来源与热更新持有者注册为 Spring Bean，
 * 供 {@code /config} 系列 HTTP 接口与程序内部共用同一个 {@link ReloadableConfig}。
 *
 * <p>可通过应用配置调整：
 * <ul>
 *     <li>{@code config.environment}：当前运行环境（默认 {@code dev}）；</li>
 *     <li>{@code config.file-path}：外部 properties 文件路径，不配置则不使用文件来源；</li>
 *     <li>{@code config.env-prefix}：环境变量命名空间前缀（默认 {@code MYAPP_}）。</li>
 * </ul>
 *
 * <p>来源顺序固定为 默认值 -> 外部文件 -> 环境变量（靠后者覆盖靠前者）。
 */
@Configuration
public class DemoLayeredConfigConfiguration {

    @Bean
    public ConfigSchema configSchema() {
        return new ConfigSchema()
                .register(ConfigKey.of("app.name", String.class).required().build())
                .register(ConfigKey.of("app.port", Integer.class)
                        .defaultValue(8080)
                        .rule(v -> (Integer) v > 0 && (Integer) v < 65536, "端口须在 1..65535")
                        .build())
                .register(ConfigKey.of("app.debug", Boolean.class)
                        .defaultValue(Boolean.FALSE)
                        .build());
    }

    @Bean
    public ReloadableConfig reloadableConfig(
            ConfigSchema schema,
            @Value("${config.environment:dev}") String environment,
            @Value("${config.env-prefix:MYAPP_}") String envPrefix,
            @Value("${config.file-path:}") String filePath) {

        List<ConfigSource> sources = new ArrayList<>();
        // 1. 默认值（最低优先级）
        sources.add(new MapConfigSource("defaults",
                Map.of("app.name", "demo", "app.port", "8080", "app.debug", "false")));
        // 2. 外部文件（可选）
        if (filePath != null && !filePath.isBlank()) {
            sources.add(new PropertiesFileSource(Path.of(filePath)));
        }
        // 3. 环境变量（命名空间内，最高优先级）
        sources.add(new EnvironmentVariableSource(envPrefix));

        return new ReloadableConfig(new LayeredConfigLoader(schema, environment), sources);
    }
}
