package com.github.highcumontoa.layeredconfigstarterjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigKey;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigManager;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigRefreshResult;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSchema;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigValueType;
import com.github.highcumontoa.layeredconfigstarterjava.config.DefaultsConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.EnvironmentVariableConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.ExternalFileConfigSource;
import com.github.highcumontoa.layeredconfigstarterjava.config.ResolvedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 并发测试：
 * 1) 刷新进行中并发读取，只能观察到“上一版”或“新版”两种完整快照，不允许半成品；
 * 2) 多线程同时刷新同一环境，只有一个线程真正执行加载（refreshed=true 仅一次）；
 * 3) 刷新失败时并发读取持续命中旧配置。
 */
class LayeredConfigConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredConfigConcurrencyTest.class);

    private ConfigSchema schema() {
        return new ConfigSchema(
                List.of(
                        new ConfigKey("app", "name", ConfigValueType.STRING, true, null, null, null, null),
                        new ConfigKey("app", "threads", ConfigValueType.INTEGER, false, "1", null, 1.0, 100000.0)),
                Set.of("dev", "prod"));
    }

    private ConfigManager manager(Path dir, Map<String, String> env) {
        return new ConfigManager(schema(), List.of(
                new DefaultsConfigSource(schema()),
                new ExternalFileConfigSource(dir, schema()),
                new EnvironmentVariableConfigSource(env, schema())));
    }

    /**
     * 测试专用门控来源：包装外部文件来源，第一次 load 时阻塞，直到测试放行；
     * 用于确定性地制造“多个刷新线程并发进入、首个仍在加载”的时间窗。
     */
    private static final class GatedFileSource implements com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSource {
        private final ExternalFileConfigSource delegate;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger loads = new AtomicInteger();

        GatedFileSource(Path dir, ConfigSchema schema) {
            this.delegate = new ExternalFileConfigSource(dir, schema);
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public int priority() {
            return delegate.priority();
        }

        @Override
        public com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSnapshot load(String environment) {
            int n = loads.getAndIncrement();
            if (n >= 1) {
                // 第 1 次是 init，直接通过；第 2 次（热刷新）进入门控窗口
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.load(environment);
        }

        void awaitFirstEntered() throws InterruptedException {
            entered.await(5, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        int loadCount() {
            return loads.get();
        }
    }

    private ConfigManager gatedManager(GatedFileSource source) {
        return new ConfigManager(schema(), List.of(
                new DefaultsConfigSource(schema()),
                source,
                new EnvironmentVariableConfigSource(Map.of(), schema())));
    }

    private void write(Path dir, String content) {
        try {
            Files.writeString(dir.resolve("config-dev.properties"), content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void concurrentRefreshLoadsOnlyOnceAndReadsNeverSeePartialState(@TempDir Path dir) throws Exception {
        write(dir, "dev.app.name=v0\n" + "dev.app.threads=1\n");
        GatedFileSource gate = new GatedFileSource(dir, schema());
        ConfigManager target = gatedManager(gate);
        target.init("dev"); // 第 1 次 load 不受门控
        write(dir, "dev.app.name=v1\n" + "dev.app.threads=100\n");

        int refreshThreads = 8;
        int readerThreads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(refreshThreads + readerThreads);
        AtomicInteger refreshedCount = new AtomicInteger();
        Set<String> observedSnapshots = ConcurrentHashMap.newKeySet();
        AtomicInteger invalidReads = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        // 先启动刷新线程：首个进入 load 后被门控挡住
        for (int i = 0; i < refreshThreads; i++) {
            futures.add(pool.submit(() -> {
                ConfigRefreshResult r = target.refresh("dev");
                if (r.refreshed()) {
                    refreshedCount.incrementAndGet();
                }
                log.info("[并发刷新] 结果 refreshed={}, version={}", r.refreshed(), r.version());
            }));
        }
        gate.awaitFirstEntered();
        // 此时首个刷新正卡在加载阶段：启动读取线程并跑一会儿，保证读到“上一版”完整快照
        for (int i = 0; i < readerThreads; i++) {
            futures.add(pool.submit(() -> {
                for (int j = 0; j < 200; j++) {
                    ResolvedConfig c = target.current("dev");
                    observedSnapshots.add(c.asMap().toString());
                    String name = c.getString("app.name").orElseThrow();
                    int t = c.getInteger("app.threads");
                    boolean consistent =
                            (name.equals("v0") && t == 1) || (name.equals("v1") && t == 100);
                    if (!consistent) {
                        invalidReads.incrementAndGet();
                    }
                }
            }));
        }
        // 给读取线程一点时间在“刷新未发布”窗口内读取
        Thread.sleep(100);
        // 放行首个刷新，其余刷新线程随后立即拿到 refreshed=false
        gate.release();

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        log.info("[判定依据] 真正执行的刷新次数={}, 来源实际加载次数={}, 观察到的快照集合={}, 不一致读取={}",
                refreshedCount.get(), gate.loadCount(), observedSnapshots, invalidReads.get());
        assertThat(refreshedCount.get()).isEqualTo(1);
        assertThat(gate.loadCount()).isEqualTo(2); // init 一次 + 真正刷新一次，其余并发刷新被跳过
        assertThat(invalidReads.get()).isZero();
        // 观察到的快照只能是“完整 v0”或“完整 v1”；按内容归一化比较，避免 Map 键顺序耦合
        Set<String> signatures = new java.util.HashSet<>();
        for (String s : observedSnapshots) {
            String sig = s.replace("app.name=", "").replace("app.threads=", "")
                    .replaceAll("[{}, ]", "");
            signatures.add(sig);
        }
        assertThat(signatures).isSubsetOf("v01", "v1100");
        assertThat(target.current("dev").getString("app.name")).contains("v1");
        assertThat(target.current("dev").getInteger("app.threads")).isEqualTo(100);
    }

    @Test
    void readersKeepUsingOldConfigWhenRefreshFails(@TempDir Path dir) throws Exception {
        write(dir, "dev.app.name=good\n" + "dev.app.threads=7\n");
        ConfigManager manager = manager(dir, Map.of());
        manager.init("dev");

        // 制造一次非法刷新（必需项缺失），同时并发读取
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(8);
        AtomicInteger invalidReads = new AtomicInteger();
        write(dir, "dev.app.threads=7\n"); // 删掉 name

        List<Future<?>> futures = new java.util.ArrayList<>();
        futures.add(pool.submit(() -> {
            try {
                manager.refresh("dev");
            } catch (Exception e) {
                log.info("[判定依据] 刷新如预期失败: reason={}, msg={}",
                        ((com.github.highcumontoa.layeredconfigstarterjava.config.ConfigException) e).getReason(),
                        e.getMessage());
            } finally {
                done.countDown();
            }
        }));
        for (int i = 0; i < 7; i++) {
            futures.add(pool.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        ResolvedConfig c = manager.current("dev");
                        if (!"good".equals(c.getString("app.name").orElse(null))
                                || c.getInteger("app.threads") != 7) {
                            invalidReads.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            }));
        }
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        log.info("[判定依据] 失败刷新期间不一致读取={}, 当前值={}", invalidReads.get(),
                manager.current("dev").asMap());
        assertThat(invalidReads.get()).isZero();
        assertThat(manager.current("dev").getString("app.name")).contains("good");
        assertThat(manager.current("dev").getInteger("app.threads")).isEqualTo(7);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
