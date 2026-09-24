package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并发安全的热更新配置持有者。
 * - 读取基于不可变快照（{@link AtomicReference} 原子切换），刷新期间不会观察到部分生效的中间状态；
 * - 刷新在校验全部通过后才切换快照，失败时整体回滚，旧快照继续可用；
 * - 刷新通过锁串行化：并发/重复触发排队执行，同一时刻只有一次加载，不会重复加载或状态错乱。
 */
public final class ReloadableConfig {

    private final LayeredConfigLoader loader;
    private final List<ConfigSource> sources;
    private final AtomicReference<ConfigSnapshot> current;
    private final Object reloadLock = new Object();

    public ReloadableConfig(LayeredConfigLoader loader, List<ConfigSource> sources) {
        this.loader = loader;
        this.sources = List.copyOf(sources);
        this.current = new AtomicReference<>(loader.load(this.sources));
    }

    /** 当前生效快照（永不为 null）。 */
    public ConfigSnapshot snapshot() {
        return current.get();
    }

    /**
     * 触发一次刷新。成功时原子切换到新快照；失败时返回失败原因，旧快照保持不变。
     */
    public ReloadResult reload() {
        synchronized (reloadLock) {
            try {
                ConfigSnapshot next = loader.load(sources);
                current.set(next);
                return ReloadResult.ok(next);
            } catch (ConfigException e) {
                return ReloadResult.failed(e);
            } catch (RuntimeException e) {
                return ReloadResult.failed(new ConfigException(
                        ConfigException.Reason.SOURCE_UNAVAILABLE, null,
                        "刷新过程中发生未预期错误，已回滚: " + e.getMessage()));
            }
        }
    }
}
