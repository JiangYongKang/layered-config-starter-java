package com.github.highcumontoa.layeredconfigstarterjava.web;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 接口触发刷新的端到端测试：
 * 成功生效、失败回滚（非法值与文件重复键两种原因）、并发调用不扰乱状态。
 * 走真实的 Spring 上下文 + MockMvc，与程序内部刷新共用同一个 ReloadableConfig Bean。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigAdminControllerTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigAdminControllerTest.class);

    /** 外部配置文件：整个测试类共享，用例按序修改它来模拟运维改配置。 */
    private static final Path CONFIG_FILE = createTempConfigFile();

    private static Path createTempConfigFile() {
        try {
            Path p = Files.createTempFile("layered-config-it", ".properties");
            Files.writeString(p, "app.name=web-demo\napp.port=8080\n");
            p.toFile().deleteOnExit();
            return p;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void appProps(DynamicPropertyRegistry registry) {
        registry.add("layered-config.file", () -> CONFIG_FILE.toString());
        registry.add("layered-config.environment", () -> "it");
        // 使用测试专属前缀，避免与运行机器上真实存在的环境变量互相干扰
        registry.add("layered-config.env-prefix", () -> "LAYERED_CONFIG_IT_");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @Order(1)
    void current_showsEffectiveValuesAndProvenance() throws Exception {
        log.info("[接口-查询] 输入: 初始文件={}（app.name=web-demo, app.port=8080）", CONFIG_FILE);
        log.info("[接口-查询] 判定依据: GET /config/current 返回生效值与来源，port 来自文件，debug 来自 defaults");

        MvcResult result = mvc.perform(get("/config/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("it"))
                .andExpect(jsonPath("$.values['app.name']").value("web-demo"))
                .andExpect(jsonPath("$.values['app.port']").value(8080))
                .andExpect(jsonPath("$.provenance['app.port']").value("file:" + CONFIG_FILE))
                .andExpect(jsonPath("$.provenance['app.debug']").value("defaults"))
                .andReturn();
        log.info("[接口-查询] 结果: {}", result.getResponse().getContentAsString());
    }

    @Test
    @Order(2)
    void reloadViaHttp_appliesNewValues() throws Exception {
        Files.writeString(CONFIG_FILE, "app.name=web-demo\napp.port=9090\n");
        log.info("[接口-刷新成功] 输入: 文件改为 app.port=9090 后 POST /config/reload");

        MvcResult reload = mvc.perform(post("/config/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.values['app.port']").value(9090))
                .andReturn();
        log.info("[接口-刷新成功] 刷新响应: {}", reload.getResponse().getContentAsString());

        MvcResult current = mvc.perform(get("/config/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values['app.port']").value(9090))
                .andExpect(jsonPath("$.provenance['app.port']").value("file:" + CONFIG_FILE))
                .andReturn();
        log.info("[接口-刷新成功] 判定依据: 刷新后 GET /config/current 生效值=9090，来源=file；结果: {}",
                current.getResponse().getContentAsString());
    }

    @Test
    @Order(3)
    void reloadViaHttp_withIllegalValue_returns409AndRollsBack() throws Exception {
        Files.writeString(CONFIG_FILE, "app.name=web-demo\napp.port=70000\n");
        log.info("[接口-失败回滚] 输入: 文件改为非法端口 70000 后 POST /config/reload");

        MvcResult reload = mvc.perform(post("/config/reload"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("ILLEGAL_VALUE"))
                .andExpect(jsonPath("$.key").value("app.port"))
                .andReturn();
        log.info("[接口-失败回滚] 刷新响应(409): {}", reload.getResponse().getContentAsString());

        MvcResult current = mvc.perform(get("/config/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values['app.port']").value(9090))
                .andReturn();
        log.info("[接口-失败回滚] 判定依据: 旧值 9090 继续生效，不出现中间状态；当前值: {}",
                current.getResponse().getContentAsString());
    }

    @Test
    @Order(4)
    void reloadViaHttp_withDuplicateKeyInFile_returns409WithDistinctReason() throws Exception {
        Files.writeString(CONFIG_FILE, "app.name=web-demo\napp.port=9091\napp.port=9092\n");
        log.info("[接口-重复键] 输入: 文件中 app.port 写两遍（9091/9092）后 POST /config/reload");

        MvcResult reload = mvc.perform(post("/config/reload"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("DUPLICATE_KEY_IN_SOURCE"))
                .andExpect(jsonPath("$.key").value("app.port"))
                .andReturn();
        log.info("[接口-重复键] 刷新响应(409): {}", reload.getResponse().getContentAsString());

        mvc.perform(get("/config/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values['app.port']").value(9090));
        log.info("[接口-重复键] 判定依据: reason=DUPLICATE_KEY_IN_SOURCE 与其它失败可区分，旧值 9090 未被部分覆盖");
    }

    @Test
    @Order(5)
    void concurrentReloadViaHttp_stateStaysConsistent() throws Exception {
        Files.writeString(CONFIG_FILE, "app.name=web-demo\napp.port=9500\n");
        int callers = 8;
        log.info("[接口-并发] 输入: 修复文件为 port=9500，{} 个线程同时 POST /config/reload", callers);
        log.info("[接口-并发] 判定依据: 全部返回 200 且 success=true，最终生效值=9500，无状态错乱");

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            tasks.add(() -> mvc.perform(post("/config/reload"))
                    .andReturn().getResponse().getStatus());
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> t : tasks) {
            futures.add(pool.submit(t));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();
        log.info("[接口-并发] 结果: 各次响应状态={}", statuses);
        assertTrue(statuses.stream().allMatch(s -> s == 200), "并发刷新应全部成功: " + statuses);

        mvc.perform(get("/config/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values['app.port']").value(9500));
        assertEquals(callers, statuses.size());
        log.info("[接口-并发] 最终生效值=9500，并发调用未扰乱内部状态");
    }
}
