package com.github.highcumontoa.layeredconfigstarterjava.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境变量命名空间过滤与键映射测试。
 * 重点：机器上同时存在大量与业务无关的环境变量时，只取命名空间内变量，其余全部忽略。
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

    @Test
    void onlyNamespacedEnvVarsAreMapped_othersIgnored() {
        // 模拟线上机器：一大堆与业务无关的系统环境变量
        Map<String, String> machineEnv = new LinkedHashMap<>();
        machineEnv.put("PATH", "/usr/local/bin:/usr/bin");
        machineEnv.put("HOME", "/home/ops");
        machineEnv.put("LANG", "en_US.UTF-8");
        machineEnv.put("JAVA_HOME", "/usr/lib/jvm/java-21");
        machineEnv.put("HOSTNAME", "prod-node-7");
        machineEnv.put("SHELL", "/bin/bash");
        machineEnv.put("LOG_LEVEL_OTHER_APP", "DEBUG");
        machineEnv.put("OTHERAPP_PORT", "9999");
        machineEnv.put("MYAPPCONF_X", "should-not-match");
        // 属于本应用命名空间的变量
        machineEnv.put("MYAPP_APP_PORT", "7070");
        machineEnv.put("MYAPP_APP_DEBUG", "true");

        log.info("[命名空间过滤] 输入: 共 {} 个环境变量，业务变量=MYAPP_APP_PORT/7070, MYAPP_APP_DEBUG/true，其余为无关变量",
                machineEnv.size());

        EnvironmentVariableSource env = new EnvironmentVariableSource("MYAPP_", () -> machineEnv);
        Map<String, String> mapped = env.load();

        log.info("[命名空间过滤] 映射结果: {}（判定依据: 只有 MYAPP_ 前缀变量进入，去前缀+转小写+下划线转点）", mapped);
        assertEquals(2, mapped.size());
        assertEquals("7070", mapped.get("app.port"));
        assertEquals("true", mapped.get("app.debug"));
        assertFalse(mapped.containsKey("path"));
        assertFalse(mapped.containsKey("java.home"));
        assertFalse(mapped.containsKey("otherapp.port"));
        assertFalse(mapped.containsKey("myappconf.x"));
        assertEquals("env", env.name());
    }

    @Test
    void envMergesWithDefaultsAndFile_withHighestPriorityAndProvenance() {
        Map<String, String> machineEnv = new LinkedHashMap<>();
        machineEnv.put("PATH", "/usr/bin");                 // 无关
        machineEnv.put("MYAPP_APP_PORT", "7070");           // 覆盖文件与默认值
        Map<String, String> defaults = Map.of("app.name", "demo", "app.port", "8080", "app.debug", "false");
        Map<String, String> file = Map.of("app.port", "9090");

        log.info("[环境变量合并] 输入: defaults={}, file={}, env(含无关 PATH)={}", defaults, file, machineEnv);

        ConfigSnapshot snapshot = new LayeredConfigLoader(schema(), "dev")
                .load(List.of(new MapConfigSource("defaults", defaults),
                        new MapConfigSource("file", file),
                        new EnvironmentVariableSource("MYAPP_", () -> machineEnv)));

        log.info("[环境变量合并] 结果: port={} provenance={}, name={} provenance={}, debug={} provenance={}",
                snapshot.getInt("app.port"), snapshot.provenance("app.port"),
                snapshot.getString("app.name"), snapshot.provenance("app.name"),
                snapshot.getBoolean("app.debug"), snapshot.provenance("app.debug"));
        assertEquals(7070, snapshot.getInt("app.port"));
        assertEquals("env", snapshot.provenance("app.port"));
        assertEquals("demo", snapshot.getString("app.name"));
        assertEquals("defaults", snapshot.provenance("app.name"));
        // debug 未由环境变量提供，defaults 中的值生效
        assertFalse(snapshot.getBoolean("app.debug"));
        assertEquals("defaults", snapshot.provenance("app.debug"));
    }

    @Test
    void unknownNamespacedVar_stillRejectedBySchema() {
        // 命名空间内、但 schema 未注册的键：不能静默忽略，要按 UNKNOWN_KEY 失败（与其它来源一致）
        Map<String, String> machineEnv = new LinkedHashMap<>();
        machineEnv.put("PATH", "/usr/bin");
        machineEnv.put("MYAPP_APP_UNKNOWN", "x");
        log.info("[命名空间内非法键] 输入: MYAPP_APP_UNKNOWN 有前缀但未在 schema 注册");

        ConfigException e = assertThrows(ConfigException.class, () ->
                new LayeredConfigLoader(schema(), "dev")
                        .load(List.of(new MapConfigSource("defaults",
                                        Map.of("app.name", "demo", "app.port", "8080")),
                                new EnvironmentVariableSource("MYAPP_", () -> machineEnv))));
        log.info("[命名空间内非法键] 判定依据: reason={}, key={}", e.reason(), e.key());
        assertEquals(ConfigException.Reason.UNKNOWN_KEY, e.reason());
        assertEquals("app.unknown", e.key());
    }
}
