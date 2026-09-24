package com.github.highcumontoa.layeredconfigstarterjava.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层加载与优先级、失败原因可区分、环境隔离的测试。
 * 每个用例打印配置输入与判定依据。
 */
class LayeredConfigLoaderTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredConfigLoaderTest.class);

    private static ConfigSchema baseSchema() {
        return new ConfigSchema()
                .register(ConfigKey.of("app.name", String.class).required().build())
                .register(ConfigKey.of("app.port", Integer.class)
                        .rule(v -> (Integer) v > 0 && (Integer) v < 65536, "端口须在 1..65535")
                        .build())
                .register(ConfigKey.of("app.debug", Boolean.class).defaultValue(Boolean.FALSE).build());
    }

    @Test
    void priority_envOverridesFileOverridesDefaults() {
        Map<String, String> defaults = Map.of("app.name", "demo", "app.port", "8080", "app.debug", "false");
        Map<String, String> file = Map.of("app.port", "9090");
        Map<String, String> env = Map.of("app.port", "7070");
        log.info("[优先级] 输入: defaults={}, file={}, env={}", defaults, file, env);
        log.info("[优先级] 判定依据: 来源列表靠后者覆盖靠前者（env > file > defaults）");

        ConfigSnapshot snapshot = new LayeredConfigLoader(baseSchema(), "dev")
                .load(List.of(new MapConfigSource("defaults", defaults),
                        new MapConfigSource("file", file),
                        new MapConfigSource("env", env)));

        log.info("[优先级] 结果: port={}, provenance={}", snapshot.getInt("app.port"),
                snapshot.provenance("app.port"));
        assertEquals(7070, snapshot.getInt("app.port"));
        assertEquals("env", snapshot.provenance("app.port"));
        assertEquals("demo", snapshot.getString("app.name"));
        assertEquals("defaults", snapshot.provenance("app.name"));
    }

    @Test
    void missingRequired_isDistinguishable() {
        Map<String, String> defaults = Map.of("app.port", "8080");
        log.info("[必需项缺失] 输入: defaults={}（缺少必需键 app.name）", defaults);

        ConfigException e = assertThrows(ConfigException.class, () ->
                new LayeredConfigLoader(baseSchema(), "dev")
                        .load(List.of(new MapConfigSource("defaults", defaults))));
        log.info("[必需项缺失] 判定依据: reason={}, key={}, message={}", e.reason(), e.key(), e.getMessage());
        assertEquals(ConfigException.Reason.MISSING_REQUIRED, e.reason());
        assertEquals("app.name", e.key());
    }

    @Test
    void typeMismatch_isDistinguishable() {
        Map<String, String> defaults = Map.of("app.name", "demo", "app.port", "abc");
        log.info("[类型不匹配] 输入: defaults={}（app.port=abc 非整数）", defaults);

        ConfigException e = assertThrows(ConfigException.class, () ->
                new LayeredConfigLoader(baseSchema(), "dev")
                        .load(List.of(new MapConfigSource("defaults", defaults))));
        log.info("[类型不匹配] 判定依据: reason={}, key={}, message={}", e.reason(), e.key(), e.getMessage());
        assertEquals(ConfigException.Reason.TYPE_MISMATCH, e.reason());
        assertEquals("app.port", e.key());
    }

    @Test
    void illegalValue_isDistinguishable() {
        Map<String, String> defaults = Map.of("app.name", "demo", "app.port", "70000");
        log.info("[取值非法] 输入: defaults={}（端口超出 1..65535）", defaults);

        ConfigException e = assertThrows(ConfigException.class, () ->
                new LayeredConfigLoader(baseSchema(), "dev")
                        .load(List.of(new MapConfigSource("defaults", defaults))));
        log.info("[取值非法] 判定依据: reason={}, key={}, message={}", e.reason(), e.key(), e.getMessage());
        assertEquals(ConfigException.Reason.ILLEGAL_VALUE, e.reason());
        assertEquals("app.port", e.key());
    }

    @Test
    void unknownKey_isDistinguishable() {
        Map<String, String> defaults = Map.of("app.name", "demo", "app.unknown", "x");
        log.info("[未知键] 输入: defaults={}（app.unknown 未注册）", defaults);

        ConfigException e = assertThrows(ConfigException.class, () ->
                new LayeredConfigLoader(baseSchema(), "dev")
                        .load(List.of(new MapConfigSource("defaults", defaults))));
        log.info("[未知键] 判定依据: reason={}, key={}, message={}", e.reason(), e.key(), e.getMessage());
        assertEquals(ConfigException.Reason.UNKNOWN_KEY, e.reason());
        assertEquals("app.unknown", e.key());
    }

    @Test
    void duplicateDefinition_isDistinguishable() {
        ConfigSchema schema = baseSchema();
        log.info("[重复定义] 输入: 再次注册 app.name");

        ConfigException e = assertThrows(ConfigException.class, () ->
                schema.register(ConfigKey.of("app.name", String.class).build()));
        log.info("[重复定义] 判定依据: reason={}, key={}, message={}", e.reason(), e.key(), e.getMessage());
        assertEquals(ConfigException.Reason.DUPLICATE_DEFINITION, e.reason());
    }

    @Test
    void crossEnvironmentMisuse_isDistinguishable() {
        ConfigSchema schema = baseSchema()
                .register(ConfigKey.of("prod.secret", String.class).environments("prod").build());
        Map<String, String> devFile = Map.of("app.name", "demo", "prod.secret", "leak");
        log.info("[跨环境误用] 输入: 当前环境=dev, 来源提供仅允许 prod 的键 prod.secret");

        ConfigException e = assertThrows(ConfigException.class, () ->
                new LayeredConfigLoader(schema, "dev")
                        .load(List.of(new MapConfigSource("file", devFile))));
        log.info("[跨环境误用] 判定依据: reason={}, key={}, message={}", e.reason(), e.key(), e.getMessage());
        assertEquals(ConfigException.Reason.CROSS_ENVIRONMENT_MISUSE, e.reason());
        assertEquals("prod.secret", e.key());
    }

    @Test
    void environmentIsolation_keysOfOtherEnvsAreAbsent() {
        ConfigSchema schema = baseSchema()
                .register(ConfigKey.of("prod.secret", String.class).environments("prod").build());
        Map<String, String> devDefaults = Map.of("app.name", "demo");
        log.info("[环境隔离] 输入: 当前环境=dev, defaults={}", devDefaults);

        ConfigSnapshot dev = new LayeredConfigLoader(schema, "dev")
                .load(List.of(new MapConfigSource("defaults", devDefaults)));
        ConfigSnapshot prod = new LayeredConfigLoader(schema, "prod")
                .load(List.of(new MapConfigSource("defaults",
                        Map.of("app.name", "demo", "prod.secret", "s3"))));

        log.info("[环境隔离] 判定依据: dev 快照不含 prod.secret={}, prod 快照含 prod.secret={}",
                dev.contains("prod.secret"), prod.contains("prod.secret"));
        assertTrue(!dev.contains("prod.secret"));
        assertTrue(prod.contains("prod.secret"));
        assertEquals("s3", prod.getString("prod.secret"));
    }
}
