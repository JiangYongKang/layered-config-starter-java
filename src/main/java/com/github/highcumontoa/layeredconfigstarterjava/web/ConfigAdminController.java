package com.github.highcumontoa.layeredconfigstarterjava.web;

import com.github.highcumontoa.layeredconfigstarterjava.config.ConfigSnapshot;
import com.github.highcumontoa.layeredconfigstarterjava.config.ReloadResult;
import com.github.highcumontoa.layeredconfigstarterjava.config.ReloadableConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置管理 HTTP 接口。
 *
 * - POST /config/reload：运行期手动触发一次刷新。与程序内部触发走的是同一个
 *   {@link ReloadableConfig}，刷新串行化、失败整体回滚的语义完全一致，
 *   并发调用只会排队执行，不会搞乱内部状态。
 * - GET /config/current：查询当前生效值及每个值最终来自哪个来源。
 *
 * 刷新失败时返回 409 及可区分的失败原因，旧配置继续可用。
 */
@RestController
@RequestMapping("/config")
public class ConfigAdminController {

    private final ReloadableConfig config;

    public ConfigAdminController(ReloadableConfig config) {
        this.config = config;
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        ReloadResult result = config.reload();
        if (result.success()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.putAll(describe(result.snapshot()));
            return ResponseEntity.ok(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("reason", result.failure().reason().name());
        body.put("key", result.failure().key());
        body.put("message", result.failure().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @GetMapping("/current")
    public Map<String, Object> current() {
        return describe(config.snapshot());
    }

    private static Map<String, Object> describe(ConfigSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environment", snapshot.environment());
        body.put("values", snapshot.asMap());
        body.put("provenance", snapshot.provenanceMap());
        return body;
    }
}
