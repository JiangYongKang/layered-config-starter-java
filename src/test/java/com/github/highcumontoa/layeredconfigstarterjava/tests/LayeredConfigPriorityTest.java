package com.github.highcumontoa.layeredconfigstarterjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigKey;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigManager;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSchema;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigValueType;
import com.github.highcumontoa.layeredconfigstarterjava.config.DefaultsConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.EnvironmentVariableConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.ExternalFileConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.ResolvedConfig;
import com.github.highcumontoa.layeredconfigstarterjava.config.ValueOrigin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 优先级冲突测试：默认值(0) &lt; 外部文件(50) &lt; 环境变量(100)。
 */
class LayeredConfigPriorityTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredConfigPriorityTest.class);

    private ConfigSchema schema() {
        return new ConfigSchema(
                List.of(
                        new ConfigKey("app", "name", ConfigValueType.STRING, true, null, null, null, null),
                        new ConfigKey("app", "threads", ConfigValueType.INTEGER, false, "4", null, 1.0, 256.0),
                        new ConfigKey("app", "mode", ConfigValueType.STRING, false, "safe",
                                Set.of("safe", "fast"), null, null)),
                Set.of("dev", "prod"));
    }

    @Test
    void priorityIsEnvOverFileOverDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config-dev.properties"),
                "dev.app.name=from-file\n"
                        + "dev.app.threads=16\n"
                        + "dev.app.mode=fast\n");
        Map<String, String> env = Map.of(
                "LAYERED_CONFIG__DEV__APP__NAME", "from-env",
                "LAYERED_CONFIG__DEV__APP__THREADS", "32");

        ConfigManager manager = new ConfigManager(schema(), List.of(
                new DefaultsConfigSource(schema()),
                new ExternalFileConfigSource(dir, schema()),
                new EnvironmentVariableConfigSource(env, schema())));

        log.info("[测试输入] file: app.name=from-file, app.threads=16, app.mode=fast");
        log.info("[测试输入] env : app.name=from-env, app.threads=32");
        log.info("[测试输入] defaults: app.threads=4, app.mode=safe");

        ResolvedConfig config = manager.init("dev");

        // 环境变量覆盖文件与默认值
        assertThat(config.getString("app.name")).contains("from-env");
        // 文件覆盖默认值
        assertThat(config.getInteger("app.threads")).isEqualTo(32);
        // 仅默认值存在
        assertThat(config.getString("app.mode")).contains("fast");

        List<ValueOrigin> nameOrigins = config.explain("app.name");
        log.info("[判定依据] app.name 候选层级 = {}", nameOrigins);
        assertThat(nameOrigins).hasSize(2);
        assertThat(nameOrigins.get(0).rawValue()).isEqualTo("from-file");
        assertThat(nameOrigins.get(1).rawValue()).isEqualTo("from-env");
        assertThat(nameOrigins.get(1).sourceName()).isEqualTo("environment-variable");

        List<ValueOrigin> threadsOrigins = config.explain("app.threads");
        log.info("[判定依据] app.threads 候选层级 = {}", threadsOrigins);
        assertThat(threadsOrigins).hasSize(3);
        assertThat(threadsOrigins.get(0).rawValue()).isEqualTo("4");
        assertThat(threadsOrigins.get(threadsOrigins.size() - 1).rawValue()).isEqualTo("32");
    }
}
