package com.github.highcumontoa.layeredconfigstarterjava.web;

import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigException;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSnapshot;
import com.github.highcumontoa.layeredconfigstarterjava.config.ReloadableConfig;
import com.github.highcumontoa.layeredconfigstarterjava.config.ReloadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置管理 HTTP 接口：
 * <ul>
 *     <li>{@code GET /config}：查看当前生效配置及每个值的最终来源；
 *         可带 {@code ?key=} 查询单个键。</li>
 *     <li>{@code POST /config/reload}：手动触发一次刷新。
 *         与程序内部调用 {@link ReloadableConfig#reload()} 使用同一个实例与同一把锁，
 *         刷新过程中读取不会看到中间状态；失败时返回可区分原因，旧配置继续生效。</li>
 * </ul>
 * 接口本身无状态：所有并发请求落到同一个 {@link ReloadableConfig}，
 * 刷新在组件内部串行化，同时触发多次也不会搞乱内部状态。
 */
@RestController
@RequestMapping("/config")
public class ConfigAdminController {

    private static final Logger log = LoggerFactory.getLogger(ConfigAdminController.class);

    private final ReloadableConfig reloadableConfig;

    public ConfigAdminController(ReloadableConfig reloadableConfig) {
        this.reloadableConfig = reloadableConfig;
    }

    /** 当前全部生效配置（含值与来源）；?key=app.port 时只返回单个键。 */
    @GetMapping
    public ConfigView current(@RequestParam(value = "key", required = false) String key) {
        ConfigSnapshot snapshot = reloadableConfig.snapshot();
        if (key != null && !key.isEmpty()) {
            if (!snapshot.contains(key)) {
                throw new UnknownConfigKeyException(key, snapshot.environment());
            }
            return ConfigView.of(snapshot.environment(),
                    List.of(EntryView.of(snapshot, key)));
        }
        List<EntryView> entries = new ArrayList<>();
        for (String k : snapshot.asMap().keySet()) {
            entries.add(EntryView.of(snapshot, k));
        }
        return ConfigView.of(snapshot.environment(), entries);
    }

    /** 手动触发刷新。成功 200；失败 409 并携带可区分失败原因，旧快照继续可用。 */
    @PostMapping("/reload")
    public ResponseEntity<ReloadResponse> reload() {
        log.info("HTTP 手动刷新触发");
        ReloadResult result = reloadableConfig.reload();
        if (result.success()) {
            ConfigSnapshot snapshot = result.snapshot();
            log.info("HTTP 手动刷新成功, environment={}", snapshot.environment());
            return ResponseEntity.ok(ReloadResponse.ok(snapshot.environment(),
                    "刷新成功，" + snapshot.asMap().size() + " 个键已生效"));
        }
        ConfigException failure = result.failure();
        log.warn("HTTP 手动刷新失败, reason={}, key={}, message={}",
                failure.reason(), failure.key(), failure.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ReloadResponse.failed(
                reloadableConfig.snapshot().environment(),
                failure.reason().name(), failure.key(), failure.getMessage(),
                "刷新失败，已整体回滚，当前生效配置仍为上一次成功加载的结果"));
    }

    @ExceptionHandler(UnknownConfigKeyException.class)
    public ResponseEntity<Map<String, String>> handleUnknownKey(UnknownConfigKeyException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "reason", ConfigException.Reason.UNKNOWN_KEY.name(),
                "key", e.getKey(),
                "environment", e.environment(),
                "message", e.getMessage()));
    }

    /** 视图对象：一次查询看到的全部生效配置。 */
    public record ConfigView(String environment, List<EntryView> entries) {
        static ConfigView of(String environment, List<EntryView> entries) {
            return new ConfigView(environment, entries);
        }
    }

    /** 视图对象：单个键的生效值与其最终来源。 */
    public record EntryView(String key, Object value, String provenance) {
        static EntryView of(ConfigSnapshot snapshot, String key) {
            return new EntryView(key, snapshot.get(key), snapshot.provenance(key));
        }
    }

    /** 视图对象：刷新结果。成功时有 detail；失败时有 reason/key/message/rollback。 */
    public record ReloadResponse(boolean success, String environment, String detail,
                                 String reason, String key, String message, String rollback) {
        static ReloadResponse ok(String environment, String detail) {
            return new ReloadResponse(true, environment, detail, null, null, null, null);
        }

        static ReloadResponse failed(String environment, String reason, String key,
                                    String message, String rollback) {
            return new ReloadResponse(false, environment, null, reason, key, message, rollback);
        }
    }

    /** 查询的键不在当前环境快照中。 */
    private static final class UnknownConfigKeyException extends RuntimeException {
        private final String key;
        private final String environment;

        UnknownConfigKeyException(String key, String environment) {
            super("键 [" + key + "] 不在当前环境 [" + environment + "] 的生效配置中");
            this.key = key;
            this.environment = environment;
        }

        String getKey() {
            return key;
        }

        String environment() {
            return environment;
        }
    }
}
