package com.github.highcumontoa.layeredconfigstarterjava.web;

import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigKey;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSchema;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.LayeredConfigLoader;
import com.github.highcumontoa.layeredconfigstarterjava.config.MapConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.ReloadableConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate.HttpClientOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 配置管理接口测试：
 * - 查询当前生效值及最终来源；
 * - 手动刷新成功后生效；
 * - 手动刷新失败时返回可区分原因、旧配置继续可用（回滚）；
 * - 并发多次触发刷新不会搞乱内部状态。
 *
 * <p>使用独立测试上下文，文件来源是一张可在测试中改写的可变 Map。
 */
@SpringBootTest(classes = ConfigAdminControllerTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigAdminControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigAdminControllerTest.class);

    /** 测试专用的可变"文件"状态：所有用例共享同一个 Spring 上下文。 */
    static final Map<String, String> FILE_STATE = new ConcurrentHashMap<>();

    static {
        // 上下文启动时 ReloadableConfig 会立即加载一次，需保证必需键存在
        FILE_STATE.put("app.name", "demo");
        FILE_STATE.put("app.port", "8080");
    }

    @LocalServerPort
    int port;

    @Autowired
    ReloadableConfig reloadableConfig;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void getCurrent_showsValuesAndProvenance() {
        FILE_STATE.clear();
        FILE_STATE.put("app.name", "demo");
        FILE_STATE.put("app.port", "8080");
        reloadableConfig.reload();

        ResponseEntity<Map> resp = new TestRestTemplate()
                .getForEntity(url("/config"), Map.class);
        log.info("[查询全部] 输入: GET /config；判定依据: HTTP 200 且 entries 含各键值与 provenance");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) resp.getBody().get("entries");
        log.info("[查询全部] 返回: {}", resp.getBody());
        assertTrue(entries.stream().anyMatch(e -> "app.port".equals(e.get("key"))
                && Integer.valueOf(8080).equals(((Number) e.get("value")).intValue())
                && "file".equals(e.get("provenance"))));

        ResponseEntity<Map> one = new TestRestTemplate()
                .getForEntity(url("/config?key=app.name"), Map.class);
        log.info("[查询单键] GET /config?key=app.name -> {}", one.getBody());
        assertEquals(HttpStatus.OK, one.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> nameEntry =
                ((List<Map<String, Object>>) one.getBody().get("entries")).get(0);
        assertEquals("demo", nameEntry.get("value"));
        assertEquals("file", nameEntry.get("provenance"));

        ResponseEntity<Map> missing = new TestRestTemplate()
                .getForEntity(url("/config?key=no.such.key"), Map.class);
        log.info("[查询不存在键] GET /config?key=no.such.key -> status={}, body={}",
                missing.getStatusCode(), missing.getBody());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("UNKNOWN_KEY", missing.getBody().get("reason"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadViaHttp_successTakesEffect() {
        FILE_STATE.clear();
        FILE_STATE.put("app.name", "demo");
        FILE_STATE.put("app.port", "8080");
        reloadableConfig.reload();
        assertEquals(8080, reloadableConfig.snapshot().getInt("app.port"));

        FILE_STATE.put("app.port", "9090");
        log.info("[接口刷新-成功] 输入: POST /config/reload，底层文件端口 8080 -> 9090");

        ResponseEntity<Map> resp = new TestRestTemplate()
                .postForEntity(url("/config/reload"), null, Map.class);
        log.info("[接口刷新-成功] 返回: status={}, body={}", resp.getStatusCode(), resp.getBody());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(Boolean.TRUE, resp.getBody().get("success"));

        ResponseEntity<Map> after = new TestRestTemplate()
                .getForEntity(url("/config?key=app.port"), Map.class);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) after.getBody().get("entries");
        log.info("[接口刷新-成功] 判定依据: 刷新后 GET 接口与内部 snapshot 同为 9090，来源为 file");
        assertEquals(Integer.valueOf(9090), ((Number) entries.get(0).get("value")).intValue());
        assertEquals(9090, reloadableConfig.snapshot().getInt("app.port"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadViaHttp_failureRollsBackWithDistinguishableReason() {
        FILE_STATE.clear();
        FILE_STATE.put("app.name", "demo");
        FILE_STATE.put("app.port", "8080");
        reloadableConfig.reload();
        int portBefore = reloadableConfig.snapshot().getInt("app.port");

        FILE_STATE.put("app.port", "70000"); // 违反端口取值约束
        log.info("[接口刷新-失败回滚] 输入: POST /config/reload，底层文件端口被改为非法值 70000");

        ResponseEntity<Map> resp = new TestRestTemplate()
                .postForEntity(url("/config/reload"), null, Map.class);
        log.info("[接口刷新-失败回滚] 返回: status={}, body={}", resp.getStatusCode(), resp.getBody());
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals(Boolean.FALSE, resp.getBody().get("success"));
        assertEquals("ILLEGAL_VALUE", resp.getBody().get("reason"));
        assertEquals("app.port", resp.getBody().get("key"));
        assertNotNull(resp.getBody().get("rollback"), "必须明确告知已回滚、旧配置继续可用");

        log.info("[接口刷新-失败回滚] 判定依据: 接口失败且 GET 接口与内部 snapshot 仍为旧值 {}", portBefore);
        ResponseEntity<Map> after = new TestRestTemplate()
                .getForEntity(url("/config?key=app.port"), Map.class);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) after.getBody().get("entries");
        assertEquals(Integer.valueOf(portBefore), ((Number) entries.get(0).get("value")).intValue());
        assertEquals(portBefore, reloadableConfig.snapshot().getInt("app.port"));
    }

    @Test
    void concurrentHttpReloads_neverCorruptState() throws InterruptedException {
        FILE_STATE.clear();
        FILE_STATE.put("app.name", "demo");
        FILE_STATE.put("app.port", "8080");
        reloadableConfig.reload();

        int threads = 10;
        int rounds = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger badStates = new AtomicInteger();
        TestRestTemplate client = new TestRestTemplate(HttpClientOption.ENABLE_REDIRECTS);

        log.info("[接口并发] 输入: {} 个线程各 POST /config/reload {} 次（端口在合法/非法间交替），判定依据: 无异常、状态始终可用",
                threads, rounds);
        for (int t = 0; t < threads; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int r = 0; r < rounds; r++) {
                    // 偶数线程写合法值，奇数线程写非法值，制造交替成功/失败
                    FILE_STATE.put("app.port", (tid % 2 == 0) ? "9090" : "70000");
                    ResponseEntity<Map> resp = client.postForEntity(url("/config/reload"), null, Map.class);
                    if (resp.getStatusCode() == HttpStatus.OK) {
                        ok.incrementAndGet();
                    } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                        conflict.incrementAndGet();
                    }
                    // 不变量：无论刷新成功失败，快照始终存在且可读取
                    if (reloadableConfig.snapshot() == null
                            || reloadableConfig.snapshot().getInt("app.port") <= 0) {
                        badStates.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(90, TimeUnit.SECONDS));
        log.info("[接口并发] 结果: 200 次数={}, 409 次数={}, 坏状态次数={}",
                ok.get(), conflict.get(), badStates.get());
        assertTrue(ok.get() > 0, "存在成功刷新");
        assertTrue(conflict.get() > 0, "存在被识别的失败刷新");
        assertEquals(0, badStates.get(), "并发刷新后内部状态始终完好、旧值继续可用");

        // 恢复合法配置，保证后续用例/上下文干净
        FILE_STATE.put("app.port", "8080");
        ResponseEntity<Map> recovered = new TestRestTemplate()
                .postForEntity(url("/config/reload"), null, Map.class);
        assertEquals(HttpStatus.OK, recovered.getStatusCode());
        assertEquals(8080, reloadableConfig.snapshot().getInt("app.port"));
    }

    /** 只装载控制器与测试用 Bean 的最小上下文，避免依赖应用自身的配置文件。 */
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @Configuration
    static class TestConfig {

        @Bean
        ConfigSchema configSchema() {
            return new ConfigSchema()
                    .register(ConfigKey.of("app.name", String.class).required().build())
                    .register(ConfigKey.of("app.port", Integer.class)
                            .rule(v -> (Integer) v > 0 && (Integer) v < 65536, "端口须在 1..65535")
                            .build());
        }

        @Bean
        ReloadableConfig reloadableConfig(ConfigSchema schema) {
            ConfigSource file = new MapConfigSource("file", FILE_STATE);
            return new ReloadableConfig(new LayeredConfigLoader(schema, "test"), List.of(file));
        }

        @Bean
        ConfigAdminController configAdminController(ReloadableConfig reloadableConfig) {
            return new ConfigAdminController(reloadableConfig);
        }
    }
}
