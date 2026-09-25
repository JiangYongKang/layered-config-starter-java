package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分层配置管理器。
 *
 * <p>语义要点：
 * <ul>
 *   <li>合并优先级：环境变量(100) &gt; 外部文件(50) &gt; 内置默认值(0)；高优先级值非法时直接失败，
 *       不会静默降级为默认值。</li>
 *   <li>每次刷新都在锁外构建并完整校验新快照，全部通过后才原子发布；构建失败则旧配置保持可用（整体回滚）。</li>
 *   <li>按环境加锁：不同环境刷新互不阻塞；同一环境并发刷新时只有一个线程真正加载，其余立即返回
 *       {@code refreshed=false}，杜绝重复加载与状态错乱。</li>
 *   <li>读取永远命中已发布的不可变 {@link ResolvedConfig}，不可能观察到部分生效状态。</li>
 * </ul>
 */
public final class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    private final ConfigSchema schema;
    private final List<ConfigSource> sources;

    private final ConcurrentHashMap<String, AtomicReference<State>> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ConfigManager(ConfigSchema schema, List<ConfigSource> sources) {
        this.schema = Objects.requireNonNull(schema, "schema");
        List<ConfigSource> sorted = new ArrayList<>(Objects.requireNonNull(sources, "sources"));
        sorted.sort(Comparator.comparingInt(ConfigSource::priority));
        this.sources = List.copyOf(sorted);
    }

    /** 初始化某环境；重复初始化直接报错，避免状态被意外重建。 */
    public ResolvedConfig init(String environment) {
        Objects.requireNonNull(environment, "environment");
        AtomicReference<State> ref = stateRef(environment);
        synchronized (ref) {
            if (ref.get() != null) {
                throw new IllegalStateException("环境已初始化，禁止重复初始化: " + environment);
            }
            ResolvedConfig resolved = build(environment);
            ref.set(new State(resolved, 1));
            log.info("[配置初始化] env={}, version=1, keys={}", environment, resolved.asMap().size());
            return resolved;
        }
    }

    /**
     * 热刷新。同一环境已有刷新在进行时，本次调用不会重复加载，返回 {@code refreshed=false}。
     * 刷新失败时异常向上抛出，旧配置原样保留。
     */
    public ConfigRefreshResult refresh(String environment) {
        Objects.requireNonNull(environment, "environment");
        AtomicReference<State> ref = stateRef(environment);
        State before = ref.get();
        if (before == null) {
            throw new IllegalStateException("环境尚未初始化，请先调用 init: " + environment);
        }
        ReentrantLock lock = locks.computeIfAbsent(environment, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("[配置刷新] 检测到并发刷新，跳过重载: env={}, version={}", environment, before.version());
            return new ConfigRefreshResult(false, before.version());
        }
        try {
            // 构建+校验与发布串行；构建失败不会触碰当前发布状态
            ResolvedConfig resolved = build(environment);
            long newVersion = before.version() + 1;
            ref.set(new State(resolved, newVersion));
            log.info("[配置刷新] 发布新配置: env={}, version={}, keys={}", environment, newVersion,
                    resolved.asMap().size());
            return new ConfigRefreshResult(true, newVersion);
        } finally {
            lock.unlock();
        }
    }

    /** 获取当前生效配置；未初始化直接报错，避免返回语义模糊的空配置。 */
    public ResolvedConfig current(String environment) {
        AtomicReference<State> ref = states.get(environment);
        State state = (ref == null) ? null : ref.get();
        if (state == null) {
            throw new IllegalStateException("环境尚未初始化: " + environment);
        }
        return state.config();
    }

    public Optional<ResolvedConfig> currentIfPresent(String environment) {
        AtomicReference<State> ref = states.get(environment);
        State state = (ref == null) ? null : ref.get();
        return Optional.ofNullable(state == null ? null : state.config());
    }

    private AtomicReference<State> stateRef(String environment) {
        return states.computeIfAbsent(environment, k -> new AtomicReference<>());
    }

    /**
     * 基于所有来源构建并完整校验一份新配置。任何异常都会使该方法失败，调用方据此整体回滚。
     */
    private ResolvedConfig build(String environment) {
        log.info("[配置构建] 开始加载: env={}, sources={}", environment, sources.stream().map(ConfigSource::name).toList());

        // 1) 逐来源加载（来源内部已做未知键/重复/跨环境检测）
        List<ConfigSnapshot> snapshots = new ArrayList<>();
        for (ConfigSource source : sources) {
            ConfigSnapshot snapshot = source.load(environment);
            log.info("[配置构建] 来源输入: source={}, priority={}, entries={}",
                    snapshot.sourceName(), snapshot.priority(), snapshot.values());
            snapshots.add(snapshot);
        }

        // 2) 逐键合并：收集各层取值，末位（最高优先级）为生效值
        Map<String, String> finalValues = new LinkedHashMap<>();
        Map<String, List<ValueOrigin>> explanations = new LinkedHashMap<>();
        for (ConfigKey key : schema.keys()) {
            String canonical = key.canonical();
            List<ValueOrigin> origins = new ArrayList<>();
            for (ConfigSnapshot snapshot : snapshots) {
                String raw = snapshot.values().get(canonical);
                if (raw != null) {
                    origins.add(new ValueOrigin(snapshot.sourceName(), snapshot.priority(), raw, null));
                }
            }
            explanations.put(canonical, List.copyOf(origins));

            String winner = origins.isEmpty() ? null : origins.get(origins.size() - 1).rawValue();
            if (winner == null) {
                if (key.required()) {
                    // 必需项在所有来源均缺失：明确失败，绝不静默降级
                    throw new ConfigException(ConfigFailureReason.REQUIRED_MISSING,
                            "必需配置项缺失: key=" + canonical + ", env=" + environment
                                    + ", 已检查来源=" + sources.stream().map(ConfigSource::name).toList());
                }
                // 非必需且无默认值：保持无值，读取时按无值处理
                continue;
            }
            validate(key, winner, environment, origins);
            finalValues.put(canonical, winner);
            log.info("[配置判定] key={}, 生效值='{}', 生效来源={}, 逐层候选={}",
                    canonical, winner, origins.get(origins.size() - 1).sourceName(),
                    origins.stream().map(o -> o.sourceName() + "='" + o.rawValue() + "'").toList());
        }

        return new ResolvedConfig(environment, schema, finalValues, explanations);
    }

    private void validate(ConfigKey key, String raw, String environment, List<ValueOrigin> origins) {
        String canonical = key.canonical();
        String winningSource = origins.get(origins.size() - 1).sourceName();

        // 类型校验
        double numeric;
        switch (key.type()) {
            case STRING -> { /* 字符串原样接受 */ }
            case INTEGER -> {
                try {
                    Integer.valueOf(raw.trim());
                    numeric = Double.parseDouble(raw.trim());
                } catch (NumberFormatException e) {
                    throw new ConfigException(ConfigFailureReason.TYPE_MISMATCH,
                            failMsg(key, raw, winningSource, "无法解析为 integer"), e);
                }
                checkRange(key, raw, winningSource, numeric);
            }
            case LONG -> {
                try {
                    Long.valueOf(raw.trim());
                    numeric = Double.parseDouble(raw.trim());
                } catch (NumberFormatException e) {
                    throw new ConfigException(ConfigFailureReason.TYPE_MISMATCH,
                            failMsg(key, raw, winningSource, "无法解析为 long"), e);
                }
                checkRange(key, raw, winningSource, numeric);
            }
            case DOUBLE -> {
                try {
                    numeric = Double.parseDouble(raw.trim());
                } catch (NumberFormatException e) {
                    throw new ConfigException(ConfigFailureReason.TYPE_MISMATCH,
                            failMsg(key, raw, winningSource, "无法解析为 double"), e);
                }
                checkRange(key, raw, winningSource, numeric);
            }
            case BOOLEAN -> {
                String b = raw.trim().toLowerCase();
                if (!b.equals("true") && !b.equals("false")) {
                    throw new ConfigException(ConfigFailureReason.TYPE_MISMATCH,
                            failMsg(key, raw, winningSource, "布尔值只允许 true/false"));
                }
            }
        }

        // 白名单校验
        if (key.allowedValues() != null && !key.allowedValues().contains(raw.trim())) {
            throw new ConfigException(ConfigFailureReason.INVALID_VALUE,
                    failMsg(key, raw, winningSource, "取值不在允许集合 " + key.allowedValues() + " 内"));
        }
    }

    private void checkRange(ConfigKey key, String raw, String source, double numeric) {
        if (key.min() != null && numeric < key.min()) {
            throw new ConfigException(ConfigFailureReason.INVALID_VALUE,
                    failMsg(key, raw, source, "小于下界 " + key.min()));
        }
        if (key.max() != null && numeric > key.max()) {
            throw new ConfigException(ConfigFailureReason.INVALID_VALUE,
                    failMsg(key, raw, source, "大于上界 " + key.max()));
        }
    }

    private static String failMsg(ConfigKey key, String raw, String source, String detail) {
        return "非法配置: key=" + key.canonical() + ", 期望类型=" + key.type()
                + ", 生效值='" + raw + "', 生效来源=" + source + ", 原因=" + detail
                + "（高优先级来源非法时不回退默认值）";
    }

    private record State(ResolvedConfig config, long version) {
    }
}
