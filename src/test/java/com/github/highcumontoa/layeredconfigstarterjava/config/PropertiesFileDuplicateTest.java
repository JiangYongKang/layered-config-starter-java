package com.github.highcumontoa.layeredconfigstarterjava.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * properties 文件同一键重复书写的识别测试。
 */
class PropertiesFileDuplicateTest {

    private static final Logger log = LoggerFactory.getLogger(PropertiesFileDuplicateTest.class);

    @Test
    void duplicateKeyInFile_isRejectedWithDistinguishableReason(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dup.properties");
        String content = """
                # 演示同一键写了两遍
                app.name=demo
                app.port=8080
                app.port=9090
                """;
        Files.writeString(file, content);
        log.info("[文件重复键] 输入文件 {} 内容:\n{}", file, content);
        log.info("[文件重复键] 判定依据: app.port 出现两次，应抛 DUPLICATE_DEFINITION，而非静默取最后值 9090");

        PropertiesFileSource source = new PropertiesFileSource(file);
        ConfigException e = assertThrows(ConfigException.class, source::load);
        log.info("[文件重复键] 结果: reason={}, key={}, message={}", e.reason(), e.key(), e.getMessage());
        assertEquals(ConfigException.Reason.DUPLICATE_DEFINITION, e.reason());
        assertEquals("app.port", e.key());
    }

    @Test
    void duplicateKeyInFile_abortsWholeLoadThroughLoader(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dup2.properties");
        String content = """
                app.name=demo
                app.port=8080
                app.debug=false
                app.name=other
                """;
        Files.writeString(file, content);
        log.info("[文件重复键-整体失败] 输入: app.name 重复（即使第二个值本身合法也必须失败）");

        ConfigSchema schema = new ConfigSchema()
                .register(ConfigKey.of("app.name", String.class).required().build())
                .register(ConfigKey.of("app.port", Integer.class).build())
                .register(ConfigKey.of("app.debug", Boolean.class).build());

        ConfigException e = assertThrows(ConfigException.class, () ->
                new LayeredConfigLoader(schema, "dev")
                        .load(List.of(new PropertiesFileSource(file))));
        assertEquals(ConfigException.Reason.DUPLICATE_DEFINITION, e.reason());
        assertEquals("app.name", e.key());
        log.info("[文件重复键-整体失败] 结果: reason={}, message={}", e.reason(), e.getMessage());
    }

    @Test
    void nonDuplicateFile_parsesCommentsContinuationAndEscapes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ok.properties");
        String content = """
                # 注释行
                ! 另一种注释
                app.name = demo
                app.greeting = hello\\ world
                app.multi = line1,\\
                            line2
                app.port:8080
                """;
        Files.writeString(file, content);
        log.info("[正常文件解析] 输入文件内容（含注释/空白/冒号分隔/续行/转义）:\n{}", content);

        Map<String, String> values = new PropertiesFileSource(file).load();
        log.info("[正常文件解析] 结果: {}", values);
        assertEquals("demo", values.get("app.name"));
        assertEquals("hello world", values.get("app.greeting"));
        assertEquals("line1,line2", values.get("app.multi"));
        assertEquals("8080", values.get("app.port"));
    }
}
