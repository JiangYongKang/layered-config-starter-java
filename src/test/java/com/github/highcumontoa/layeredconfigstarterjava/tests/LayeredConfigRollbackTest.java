package com.github.highcumontoa.layeredconfigstarterjava.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigException;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigFailureReason;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigKey;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigManager;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSchema;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigValueType;
import com.github.highcumontoa.layeredconfigstarterjava.config.DefaultsConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.EnvironmentVariableConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.ExternalFileConfigSource;
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
 * 非法配置处理测试：
 * 1) 必需项缺失 / 类型不匹配 / 取值非法 / 未知键 / 重复键 / 跨环境误用，失败原因可区分；
 * 2) 高优先级值非法时不静默回退默认值；
 * 3) 刷新失败时旧配置完整保留（整体回滚）。
 */
class LayeredConfigRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredConfigRollbackTest.class);

    private ConfigSchema schema() {
        return new ConfigSchema(
                List.of(
                        new ConfigKey("app", "name", ConfigValueType.STRING, true, null, null, null, null),
                        new ConfigKey("app", "threads", ConfigValueType.INTEGER, false, "4", null, 1.0, 256.0),
                        new ConfigKey("app", "mode", ConfigValueType.STRING, false, "safe",
                                Set.of("safe", "fast"), null, null)),
                Set.of("dev", "prod"));
    }

    private ConfigManager manager(Path dir, Map<String, String> env) {
        return new ConfigManager(schema(), List.of(
                new DefaultsConfigSource(schema()),
                new ExternalFileConfigSource(dir, schema()),
                new EnvironmentVariableConfigSource(env, schema())));
    }

    private void assertReason(ThrowingRunnable r, ConfigFailureReason reason) {
        assertThatThrownBy(r::run)
                .isInstanceOfSatisfying(ConfigException.class,
                        e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    @Test
    void failureReasonsAreDistinguishable(@TempDir Path dir) throws Exception {
        // 必需项缺失：文件里没有 app.name
        write(dir, "config-dev.properties", "dev.app.threads=8\n");
        ConfigManager m1 = manager(dir, Map.of());
        log.info("[测试输入] 必需项缺失用例文件内容:\n{}", Files.readString(dir.resolve("config-dev.properties")));
        assertReason(() -> m1.init("dev"), ConfigFailureReason.REQUIRED_MISSING);

        // 类型不匹配：threads 写成非数字（且不回退默认值 4）
        write(dir, "config-dev.properties", "dev.app.name=x\n" + "dev.app.threads=abc\n");
        ConfigManager m2 = manager(dir, Map.of());
        log.info("[测试输入] 类型不匹配用例: app.threads=abc（默认值为 4）");
        assertReason(() -> m2.init("dev"), ConfigFailureReason.TYPE_MISMATCH);

        // 取值非法：越界
        write(dir, "config-dev.properties", "dev.app.name=x\n" + "dev.app.threads=999\n");
        ConfigManager m3 = manager(dir, Map.of());
        log.info("[测试输入] 非法值用例: app.threads=999（允许区间 1..256）");
        assertReason(() -> m3.init("dev"), ConfigFailureReason.INVALID_VALUE);

        // 取值非法：白名单
        write(dir, "config-dev.properties", "dev.app.name=x\n" + "dev.app.mode=turbo\n");
        ConfigManager m4 = manager(dir, Map.of());
        log.info("[测试输入] 非法值用例: app.mode=turbo（允许 safe/fast）");
        assertReason(() -> m4.init("dev"), ConfigFailureReason.INVALID_VALUE);

        // 未知键
        write(dir, "config-dev.properties", "dev.app.name=x\n" + "dev.unknown.key=1\n");
        ConfigManager m5 = manager(dir, Map.of());
        log.info("[测试输入] 未知键用例: dev.unknown.key=1");
        assertReason(() -> m5.init("dev"), ConfigFailureReason.UNKNOWN_KEY);

        // 文件内重复定义
        write(dir, "config-dev.properties",
                "dev.app.name=x\n" + "dev.app.name=y\n");
        ConfigManager m6 = manager(dir, Map.of());
        log.info("[测试输入] 重复定义用例: app.name 出现两次");
        assertReason(() -> m6.init("dev"), ConfigFailureReason.DUPLICATE_KEY);

        // 跨环境误用：dev 文件中出现 prod. 前缀
        write(dir, "config-dev.properties",
                "dev.app.name=x\n" + "prod.app.name=leak\n");
        ConfigManager m7 = manager(dir, Map.of());
        log.info("[测试输入] 跨环境误用用例: dev 文件中写入 prod.app.name");
        assertReason(() -> m7.init("dev"), ConfigFailureReason.CROSS_ENVIRONMENT);
    }

    @Test
    void invalidHighPriorityValueDoesNotFallBackToDefault(@TempDir Path dir) {
        write(dir, "config-dev.properties", "dev.app.name=x\n");
        // 环境变量给 threads 一个非法值：必须失败，而不是采用文件/默认值
        ConfigManager m = manager(dir, Map.of("LAYERED_CONFIG__DEV__APP__THREADS", "oops"));
        log.info("[测试输入] 高优先级非法用例: env threads=oops, default=4");
        assertReason(() -> m.init("dev"), ConfigFailureReason.TYPE_MISMATCH);
    }

    @Test
    void failedRefreshRollsBackAndKeepsOldConfig(@TempDir Path dir) {
        write(dir, "config-dev.properties",
                "dev.app.name=v1\n" + "dev.app.threads=8\n");
        ConfigManager m = manager(dir, Map.of());
        var v1 = m.init("dev");
        log.info("[测试输入] 初始配置: app.name=v1, app.threads=8");
        assertThat(v1.getString("app.name")).contains("v1");
        assertThat(v1.getInteger("app.threads")).isEqualTo(8);

        // 刷新内容非法：缺失必需项
        write(dir, "config-dev.properties", "dev.app.threads=8\n");
        log.info("[测试输入] 第一次刷新：删除 app.name（必需项缺失，预期回滚）");
        assertReason(() -> m.refresh("dev"), ConfigFailureReason.REQUIRED_MISSING);

        // 旧配置仍可用且未被部分改写
        assertThat(m.current("dev").getString("app.name")).contains("v1");
        assertThat(m.current("dev").getInteger("app.threads")).isEqualTo(8);
        assertThat(m.current("dev")).isSameAs(v1);
        log.info("[判定依据] 刷新失败后 current 仍指向 v1，app.name={}", m.current("dev").getString("app.name"));

        // 再给一份合法的新配置，刷新成功并整体生效
        write(dir, "config-dev.properties",
                "dev.app.name=v2\n" + "dev.app.threads=16\n" + "dev.app.mode=fast\n");
        log.info("[测试输入] 第二次刷新：app.name=v2, app.threads=16, mode=fast（预期成功）");
        var result = m.refresh("dev");
        assertThat(result.refreshed()).isTrue();
        assertThat(result.version()).isEqualTo(2);
        assertThat(m.current("dev").getString("app.name")).contains("v2");
        assertThat(m.current("dev").getInteger("app.threads")).isEqualTo(16);
        assertThat(m.current("dev").getString("app.mode")).contains("fast");
        log.info("[判定依据] 刷新成功后 version={}, values={}", result.version(), m.current("dev").asMap());
    }

    private static void write(Path dir, String file, String content) {
        try {
            Files.writeString(dir.resolve(file), content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
