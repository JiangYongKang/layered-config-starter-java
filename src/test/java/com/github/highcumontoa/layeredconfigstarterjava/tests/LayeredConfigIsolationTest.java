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
 * 隔离测试：
 * 1) 不同环境配置互不污染，各自独立刷新；
 * 2) 不同作用域同名键互不干扰；
 * 3) 跨环境误用（文件/环境变量）被明确拒绝；
 * 4) 多轮刷新后隔离结论仍然成立。
 */
class LayeredConfigIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredConfigIsolationTest.class);

    private ConfigSchema schema() {
        return new ConfigSchema(
                List.of(
                        // 两个作用域下同名键 name，验证作用域隔离
                        new ConfigKey("app", "name", ConfigValueType.STRING, true, null, null, null, null),
                        new ConfigKey("service", "name", ConfigValueType.STRING, true, null, null, null, null)),
                Set.of("dev", "prod"));
    }

    private ConfigManager manager(Path dir, Map<String, String> env) {
        return new ConfigManager(schema(), List.of(
                new DefaultsConfigSource(schema()),
                new ExternalFileConfigSource(dir, schema()),
                new EnvironmentVariableConfigSource(env, schema())));
    }

    @Test
    void environmentsDoNotPolluteEachOtherAcrossRefreshes(@TempDir Path dir) {
        write(dir, "config-dev.properties", "dev.app.name=dev-app\n" + "dev.service.name=dev-svc\n");
        write(dir, "config-prod.properties", "prod.app.name=prod-app\n" + "prod.service.name=prod-svc\n");

        // 环境变量里同时带 dev 与 prod 的值，验证加载各环境时只取本环境段
        Map<String, String> env = Map.of(
                "LAYERED_CONFIG__DEV__SERVICE__NAME", "dev-env-svc",
                "LAYERED_CONFIG__PROD__SERVICE__NAME", "prod-env-svc");

        ConfigManager m = manager(dir, env);
        var dev = m.init("dev");
        var prod = m.init("prod");
        log.info("[测试输入] dev 初始: {}", dev.asMap());
        log.info("[测试输入] prod 初始: {}", prod.asMap());

        assertThat(dev.getString("app.name")).contains("dev-app");
        assertThat(dev.getString("service.name")).contains("dev-env-svc");
        assertThat(prod.getString("app.name")).contains("prod-app");
        assertThat(prod.getString("service.name")).contains("prod-env-svc");

        // 多轮只刷新 dev：prod 必须纹丝不动；dev 版本增长而 prod 版本不变
        // 跟踪 app.name（该键无高优先级环境变量覆盖，文件刷新可改变）
        for (int i = 0; i < 3; i++) {
            write(dir, "config-dev.properties",
                    "dev.app.name=dev-app-v" + (i + 2) + "\n" + "dev.service.name=dev-svc\n");
            var r = m.refresh("dev");
            log.info("[判定依据] 第{}次刷新 dev: refreshed={}, version={}", i + 1, r.refreshed(), r.version());
            assertThat(r.refreshed()).isTrue();

            assertThat(m.current("dev").getString("app.name")).contains("dev-app-v" + (i + 2));
            // env 最高优先级仍生效
            assertThat(m.current("dev").getString("service.name")).contains("dev-env-svc");
            // prod 完全不受影响
            assertThat(m.current("prod").getString("app.name")).contains("prod-app");
            assertThat(m.current("prod").getString("service.name")).contains("prod-env-svc");
            assertThat(m.current("prod")).isSameAs(prod);
        }

        // 再刷新 prod，dev 不受影响
        write(dir, "config-prod.properties", "prod.app.name=prod-app-2\n" + "prod.service.name=prod-svc-2\n");
        m.refresh("prod");
        assertThat(m.current("prod").getString("app.name")).contains("prod-app-2");
        assertThat(m.current("dev").getString("app.name")).contains("dev-app-v4");
        log.info("[判定依据] 多轮刷新后 dev={}, prod={}",
                m.current("dev").asMap(), m.current("prod").asMap());
    }

    @Test
    void scopesKeepSameNameKeysDistinct(@TempDir Path dir) {
        write(dir, "config-dev.properties", "dev.app.name=A\n" + "dev.service.name=B\n");
        ConfigManager m = manager(dir, Map.of());
        var c = m.init("dev");
        assertThat(c.getString("app.name")).contains("A");
        assertThat(c.getString("service.name")).contains("B");
        log.info("[判定依据] 作用域隔离 app.name={}, service.name={}",
                c.getString("app.name"), c.getString("service.name"));
    }

    @Test
    void otherEnvironmentEnvVarsAreFilteredAndUnknownOnesRejected(@TempDir Path dir) {
        write(dir, "config-dev.properties", "dev.app.name=A\n" + "dev.service.name=B\n");

        // 1) 加载 dev 时，prod 段环境变量必须被过滤，不得泄漏进 dev
        Map<String, String> env = Map.of("LAYERED_CONFIG__PROD__APP__NAME", "leak");
        ConfigManager m = manager(dir, env);
        log.info("[测试输入] 加载 dev，同时存在 PROD 段环境变量（预期被过滤）");
        var dev = m.init("dev");
        assertThat(dev.getString("app.name")).contains("A");
        assertThat(dev.explain("app.name")).noneMatch(o -> o.rawValue().equals("leak"));
        log.info("[判定依据] dev app.name={}，来源={}（无 prod 泄漏）",
                dev.getString("app.name"), dev.explain("app.name"));

        // 2) 环境段不是任何已知环境：明确标记为未知键，而非静默忽略
        Map<String, String> badEnv = Map.of("LAYERED_CONFIG__STAGING__APP__NAME", "x");
        ConfigManager m2 = manager(dir, badEnv);
        log.info("[测试输入] 环境段 STAGING 不在已知环境集合（预期 UNKNOWN_KEY）");
        assertThatThrownBy(() -> m2.init("dev"))
                .isInstanceOfSatisfying(ConfigException.class,
                        e -> assertThat(e.getReason()).isEqualTo(ConfigFailureReason.UNKNOWN_KEY));
    }

    private static void write(Path dir, String file, String content) {
        try {
            Files.writeString(dir.resolve(file), content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
