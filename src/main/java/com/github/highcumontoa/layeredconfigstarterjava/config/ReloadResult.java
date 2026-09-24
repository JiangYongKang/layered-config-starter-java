package com.github.highcumontoa.layeredconfigstarterjava.config;

/**
 * 一次刷新的结果：成功（含新快照）或失败（含可区分原因，旧快照继续生效）。
 */
public final class ReloadResult {

    private final boolean success;
    private final ConfigSnapshot snapshot;
    private final ConfigException failure;

    private ReloadResult(boolean success, ConfigSnapshot snapshot, ConfigException failure) {
        this.success = success;
        this.snapshot = snapshot;
        this.failure = failure;
    }

    public static ReloadResult ok(ConfigSnapshot snapshot) {
        return new ReloadResult(true, snapshot, null);
    }

    public static ReloadResult failed(ConfigException failure) {
        return new ReloadResult(false, null, failure);
    }

    public boolean success() { return success; }
    public ConfigSnapshot snapshot() { return snapshot; }
    public ConfigException failure() { return failure; }
}
