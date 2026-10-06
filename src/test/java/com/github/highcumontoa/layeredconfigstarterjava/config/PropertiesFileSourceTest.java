package com.github.highcumontoa.layeredconfigstarterjava.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 外部 properties 文件来源测试：重复键被明确识别（DUPLICATE_KEY_IN_SOURCE），
 * 正常文件解析行为与注释/续行兼容。
 */
class PropertiesFileSourceTest {

    private static final Logger log = LoggerFactory.getLogger(PropertiesFileSourceTest.class);

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void duplicateKey_isDetectedWithDistinguishableReason() throws IOException {
        Path file = write("dup.properties", """
                app.name=demo
                app.port=8080
                app.port=9090
                """);
        log.info("[文件重复键] 输入: 文件 {} 中 app.port 写了两遍（8080 与 9090）", file);
        log.info("[文件重复键] 判定依据: 必须抛出 DUPLICATE_KEY_IN_SOURCE，而不是静默采用最后一条 9090");

        ConfigException e = assertThrows(ConfigException.class,
                () -> new PropertiesFileSource(file).load());
        log.info("[文件重复键] 结果: reason={}, key={}, message={}", e.reason(), e.key(), e.getMessage());

        assertEquals(ConfigException.Reason.DUPLICATE_KEY_IN_SOURCE, e.reason());
        assertEquals("app.port", e.key());
    }

    @Test
    void duplicateKey_viaReload_rollsBackAndKeepsOldSnapshot() throws IOException {
        Path file = write("reload-dup.properties", "app.name=demo\napp.port=8080\n");
        ConfigSchema schema = new ConfigSchema()
                .register(ConfigKey.of("app.name", String.class).required().build())
                .register(ConfigKey.of("app.port", Integer.class).build());
        ReloadableConfig config = new ReloadableConfig(new LayeredConfigLoader(schema, "dev"),
                java.util.List.of(new PropertiesFileSource(file)));
        log.info("[重复键回滚] 初始快照: port={}", config.snapshot().getInt("app.port"));

        Files.writeString(file, "app.name=demo\napp.port=9090\napp.port=7070\n");
        log.info("[重复键回滚] 刷新输入: 文件被改成 app.port 重复定义（9090 与 7070）");
        ReloadResult result = config.reload();

        log.info("[重复键回滚] 判定依据: success={}, reason={}, 旧快照 port={} 必须继续生效",
                result.success(), result.failure().reason(), config.snapshot().getInt("app.port"));
        org.junit.jupiter.api.Assertions.assertFalse(result.success());
        assertEquals(ConfigException.Reason.DUPLICATE_KEY_IN_SOURCE, result.failure().reason());
        assertEquals(8080, config.snapshot().getInt("app.port"));
    }

    @Test
    void normalFile_parsesCommentsContinuationsAndSeparators() throws IOException {
        Path file = write("normal.properties", """
                # 注释行
                ! 另一种注释
                app.name=demo
                app.port: 9090
                app.debug true
                app.desc=line1\\
                  line2
                """);
        log.info("[正常解析] 输入: 含注释、'='、':'、空白分隔符与反斜杠续行的文件 {}", file);
        log.info("[正常解析] 判定依据: 四种写法都能解析且无重复键误报");

        Map<String, String> map = new PropertiesFileSource(file).load();
        log.info("[正常解析] 结果: {}", map);

        assertEquals("demo", map.get("app.name"));
        assertEquals("9090", map.get("app.port"));
        assertEquals("true", map.get("app.debug"));
        assertEquals("line1line2", map.get("app.desc"));
    }

    @Test
    void missingFile_isSourceUnavailable() {
        Path file = dir.resolve("not-exists.properties");
        log.info("[文件缺失] 输入: 不存在的文件 {}", file);

        ConfigException e = assertThrows(ConfigException.class,
                () -> new PropertiesFileSource(file).load());
        log.info("[文件缺失] 判定依据: reason={}, message={}", e.reason(), e.getMessage());
        assertEquals(ConfigException.Reason.SOURCE_UNAVAILABLE, e.reason());
    }
}
