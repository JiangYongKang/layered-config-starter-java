package com.github.highcumontoa.layeredconfigstarterjava.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 环境变量来源测试：命名空间过滤、键映射、与默认值/外部文件合并后的优先级与可解释性。
 */
class EnvironmentVariableSourceTest {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentVariableSourceTest.class);

    private static ConfigSchema schema() {
        return new ConfigSchema()
                .register(ConfigKey.of("app.name", String.class).required().build())
                .register(ConfigKey.of("app.port", Integer.class)
                        .rule(v -> (Integer) v > 0 && (Integer) v < 65536, "端口须在 1..65535")
                        .build())
                .register(ConfigKey.of("app.debug", Boolean.class).defaultValue(Boolean.FALSE).build());
    }

    /** 模拟一台真实运行机器：大量与业务无关的环境变量。 */
    private static Map<String, String> machineEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/local/bin:/usr/bin");
        env.put("HOME", "/home/app");
        env.put("JAVA_HOME", "/usr/lib/jvm/java-21");
        env.put("LANG", "en_US.UTF-8");
        env.put("HOSTNAME", "prod-node-17");
        env.put("KUBERNETES_SERVICE_HOST", "10.96.0.1");
        env.put("KUBERNETES_SERVICE_PORT", "443");
        env.put("SSH_AUTH_SOCK", "/tmp/ssh-agent.sock");
        env.put("APPPORT", "9999");          // 无前缀、仅形似，必须忽略
        env.put("APPLICATION_PORT", "9998"); // 无前缀，必须忽略
        // 应用约定命名空间 APP_ 内的变量
        env.put("APP_APP_PORT", "7070");
        env.put("APP_APP_DEBUG", "true");
        return env;
    }

    @Test
    void onlyPrefixedVariablesArePickedUp_unrelatedOnesIgnored() {
        Map<String, String> env = machineEnv();
        log.info("[环境变量过滤] 输入: 机器环境变量共 {} 个，其中约定前缀 APP_ 内 2 个，其余为无关变量", env.size());
        log.info("[环境变量过滤] 判定依据: 仅 APP_ 前缀变量被映射（APP_APP_PORT->app.port, APP_APP_DEBUG->app.debug），"
                + "PATH/HOME/APPPORT/APPLICATION_PORT 等一律忽略且不报错");

        Map<String, String> loaded = new EnvironmentVariableSource("APP_", () -> env).load();
        log.info("[环境变量过滤] 结果: 映射得到的配置键值={}", loaded);

        assertEquals(Map.of("app.port", "7070", "app.debug", "true"), loaded);
        assertFalse(loaded.containsKey("path"));
        assertFalse(loaded.containsKey("appport"));
        assertFalse(loaded.containsKey("application.port"));
    }

    @Test
    void mergedWithDefaultsAndFile_envWinsAndProvenanceExplainable() {
        Map<String, String> defaults = Map.of("app.name", "demo", "app.port", "8080", "app.debug", "false");
        Map<String, String> file = Map.of("app.port", "9090");
        Map<String, String> env = machineEnv();
        log.info("[环境变量合并] 输入: defaults={}, file={}, env(过滤后)={{app.port=7070, app.debug=true}}",
                defaults, file);
        log.info("[环境变量合并] 判定依据: env > file > defaults；provenance 指向最终来源 env(APP_*)");

        ConfigSnapshot snapshot = new LayeredConfigLoader(schema(), "prod")
                .load(List.of(new MapConfigSource("defaults", defaults),
                        new MapConfigSource("file:app.properties", file),
                        new EnvironmentVariableSource("APP_", () -> env)));

        log.info("[环境变量合并] 结果: port={} (来自 {}), debug={} (来自 {}), name={} (来自 {})",
                snapshot.getInt("app.port"), snapshot.provenance("app.port"),
                snapshot.getBoolean("app.debug"), snapshot.provenance("app.debug"),
                snapshot.getString("app.name"), snapshot.provenance("app.name"));

        assertEquals(7070, snapshot.getInt("app.port"));
        assertEquals("env(APP_*)", snapshot.provenance("app.port"));
        assertEquals(true, snapshot.getBoolean("app.debug"));
        assertEquals("env(APP_*)", snapshot.provenance("app.debug"));
        assertEquals("demo", snapshot.getString("app.name"));
        assertEquals("defaults", snapshot.provenance("app.name"));
    }

    @Test
    void emptyPrefix_isRejected() {
        log.info("[空前缀] 判定依据: 空前缀会把机器上所有无关变量当作配置键，必须拒绝");
        ConfigException e = assertThrows(ConfigException.class,
                () -> new EnvironmentVariableSource(""));
        log.info("[空前缀] 结果: reason={}, message={}", e.reason(), e.getMessage());
        assertEquals(ConfigException.Reason.ILLEGAL_VALUE, e.reason());
    }
}
