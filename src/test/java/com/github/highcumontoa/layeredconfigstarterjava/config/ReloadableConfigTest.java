package com.github.highcumontoa.layeredconfigstarterjava.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热更新语义测试：非法配置回滚、并发刷新与读取、重复刷新不重复加载。
 */
class ReloadableConfigTest {

    private static final Logger log = LoggerFactory.getLogger(ReloadableConfigTest.class);

    private static ConfigSchema schema() {
        return new ConfigSchema()
                .register(ConfigKey.of("app.name", String.class).required().build())
                .register(ConfigKey.of("app.port", Integer.class)
                        .rule(v -> (Integer) v > 0 && (Integer) v < 65536, "端口须在 1..65535")
                        .build());
    }

    @Test
    void invalidReload_rollsBackAndKeepsOldSnapshot() {
        Map<String, String> fileState = new ConcurrentHashMap<>(
                Map.of("app.name", "demo", "app.port", "8080"));
        ReloadableConfig config = new ReloadableConfig(new LayeredConfigLoader(schema(), "dev"),
                List.of(new MapConfigSource("file", fileState)));
        ConfigSnapshot before = config.snapshot();
        log.info("[回滚] 初始快照: port={}", before.getInt("app.port"));

        fileState.put("app.port", "70000"); // 非法值
        log.info("[回滚] 刷新输入: fileState={}（端口非法）", fileState);
        ReloadResult result = config.reload();

        log.info("[回滚] 判定依据: success={}, reason={}, 当前 port={}",
                result.success(), result.failure().reason(), config.snapshot().getInt("app.port"));
        assertFalse(result.success());
        assertEquals(ConfigException.Reason.ILLEGAL_VALUE, result.failure().reason());
        assertSame(before, config.snapshot(), "刷新失败后旧快照必须继续生效");
        assertEquals(8080, config.snapshot().getInt("app.port"));

        fileState.put("app.port", "9090"); // 修复后再次刷新成功
        ReloadResult fixed = config.reload();
        log.info("[回滚] 修复后刷新: success={}, port={}", fixed.success(),
                config.snapshot().getInt("app.port"));
        assertTrue(fixed.success());
        assertEquals(9090, config.snapshot().getInt("app.port"));
    }

    @Test
    void concurrentReloadAndRead_neverObservePartialState() throws InterruptedException {
        Map<String, String> fileState = new ConcurrentHashMap<>(
                Map.of("app.name", "v0", "app.port", "8000"));
        ReloadableConfig config = new ReloadableConfig(new LayeredConfigLoader(schema(), "dev"),
                List.of(new MapConfigSource("file", fileState)));

        int reloaders = 4;
        int readers = 8;
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(reloaders + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inconsistencies = new AtomicInteger();
        AtomicInteger reloadCount = new AtomicInteger();

        // 一致性不变量：app.name 为 "vN" 时 app.port 必须为 8000+N（两个键同批写入）
        for (int i = 0; i < reloaders; i++) {
            int id = i;
            pool.submit(() -> {
                await(start);
                for (int r = 1; r <= rounds; r++) {
                    int n = id * rounds + r;
                    fileState.put("app.name", "v" + n);
                    fileState.put("app.port", String.valueOf(8000 + n));
                    ReloadResult result = config.reload();
                    if (result.success()) {
                        reloadCount.incrementAndGet();
                    }
                }
            });
        }
        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                await(start);
                for (int r = 0; r < rounds * reloaders; r++) {
                    ConfigSnapshot snap = config.snapshot();
                    String name = snap.getString("app.name");
                    int port = snap.getInt("app.port");
                    int n = Integer.parseInt(name.substring(1));
                    if (port != 8000 + n) {
                        inconsistencies.incrementAndGet();
                        log.error("[并发] 观察到中间状态: name={}, port={}", name, port);
                    }
                }
            });
        }
        log.info("[并发] 输入: {} 个刷新线程 x {} 轮, {} 个读取线程；判定依据: name=vN 时 port 必须等于 8000+N",
                reloaders, rounds, readers);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        log.info("[并发] 结果: 成功刷新次数={}, 不一致观察次数={}", reloadCount.get(), inconsistencies.get());
        assertEquals(0, inconsistencies.get(), "并发读取不得观察到部分生效的中间状态");
        assertTrue(reloadCount.get() > 0);
        assertNotNull(config.snapshot());
    }

    @Test
    void repeatedReloads_areSerializedAndConsistent() {
        Map<String, String> fileState = new ConcurrentHashMap<>(
                Map.of("app.name", "demo", "app.port", "8080"));
        AtomicInteger loadInvocations = new AtomicInteger();
        ConfigSource counting = new ConfigSource() {
            @Override
            public String name() { return "counting"; }
            @Override
            public Map<String, String> load() {
                loadInvocations.incrementAndGet();
                return fileState;
            }
        };
        ReloadableConfig config = new ReloadableConfig(new LayeredConfigLoader(schema(), "dev"),
                List.of(counting));
        assertEquals(1, loadInvocations.get(), "构造时加载一次");

        ReloadResult r1 = config.reload();
        ReloadResult r2 = config.reload();
        log.info("[重复刷新] 输入: 连续两次 reload；判定依据: 各自串行加载一次且均成功");
        log.info("[重复刷新] 结果: r1={}, r2={}, loadInvocations={}",
                r1.success(), r2.success(), loadInvocations.get());
        assertTrue(r1.success());
        assertTrue(r2.success());
        assertEquals(3, loadInvocations.get(), "每次刷新恰好加载一次，不重复不遗漏");
        assertEquals(8080, config.snapshot().getInt("app.port"));
    }

    @Test
    void isolationHoldsAcrossMultipleReloads() {
        ConfigSchema schema = schema()
                .register(ConfigKey.of("prod.secret", String.class).environments("prod").build());
        Map<String, String> devState = new ConcurrentHashMap<>(
                Map.of("app.name", "demo", "app.port", "8080"));
        ReloadableConfig dev = new ReloadableConfig(new LayeredConfigLoader(schema, "dev"),
                List.of(new MapConfigSource("file", devState)));
        log.info("[隔离持久性] 输入: dev 环境连续刷新 5 次，schema 含仅 prod 可用的 prod.secret");

        for (int i = 1; i <= 5; i++) {
            devState.put("app.port", String.valueOf(8080 + i));
            ReloadResult result = dev.reload();
            assertTrue(result.success());
            assertFalse(dev.snapshot().contains("prod.secret"),
                    "第 " + i + " 次刷新后 dev 快照仍不得包含 prod.secret");
        }
        log.info("[隔离持久性] 判定依据: 5 次刷新后 dev 快照始终不含 prod.secret，最终 port={}",
                dev.snapshot().getInt("app.port"));
        assertEquals(8085, dev.snapshot().getInt("app.port"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
