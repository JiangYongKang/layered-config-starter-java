package com.github.highcumontoa.layeredconfigstarterjava.web;

import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigException;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigManager;
import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigRefreshResult;
import com.github.highcumontoa.layeredconfigstarterjava.config.ResolvedConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置 HTTP 入口：与编程式入口（直接注入 {@link ConfigManager}）共享同一实例与同一套语义。
 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    private final ConfigManager configManager;

    public ConfigController(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** 查看某环境全部生效配置。 */
    @GetMapping("/{environment}")
    public Map<String, Object> all(@PathVariable String environment) {
        ResolvedConfig config = configManager.current(environment);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environment", config.environment());
        body.put("values", config.asMap());
        return body;
    }

    /** 查看单个键的值与逐层判定依据。 */
    @GetMapping("/{environment}/key/{scope}/{name}")
    public Map<String, Object> key(@PathVariable String environment,
                                   @PathVariable String scope,
                                   @PathVariable String name) {
        ResolvedConfig config = configManager.current(environment);
        String canonical = scope + "." + name;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", canonical);
        body.put("value", config.getString(canonical).orElse(null));
        body.put("explanation", config.explain(canonical));
        return body;
    }

    /** 触发热刷新。 */
    @PostMapping("/{environment}/refresh")
    public Map<String, Object> refresh(@PathVariable String environment,
                                       @RequestParam(defaultValue = "false") boolean wait)
            throws InterruptedException {
        // HTTP 层提供简单的串行重试语义，便于调用方观察“并发刷新只加载一次”
        ConfigRefreshResult result = null;
        if (wait) {
            for (int i = 0; i < 50; i++) {
                result = configManager.refresh(environment);
                if (result.refreshed()) {
                    break;
                }
                Thread.sleep(20);
            }
        } else {
            result = configManager.refresh(environment);
        }
        return Map.of("refreshed", result.refreshed(), "version", result.version());
    }

    /** 将配置失败原因映射为可区分的错误码（reason 字段）。 */
    @ExceptionHandler(ConfigException.class)
    public ResponseEntity<Map<String, String>> handleConfig(ConfigException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("reason", e.getReason().name(), "message", e.getMessage()));
    }

    /** 环境未初始化等状态类问题映射为 409。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("reason", "ILLEGAL_STATE", "message", e.getMessage()));
    }
}
