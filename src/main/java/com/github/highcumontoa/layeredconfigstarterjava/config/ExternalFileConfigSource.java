package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外部文件来源（优先级 50，居中）。
 *
 * <p>文件位置：{@code ${baseDir}/config-${environment}.properties}；文件不存在时返回空快照
 * （不视为失败，必需项缺失由合并阶段统一判定）。
 *
 * <p>文件内键必须带环境前缀，形如 {@code dev.app.name=...}（即 {@code 环境.作用域.键名}）：
 * <ul>
 *   <li>同一文件内重复定义同一键 → {@link ConfigFailureReason#DUPLICATE_KEY}</li>
 *   <li>前缀是已知的“其他”环境 → {@link ConfigFailureReason#CROSS_ENVIRONMENT}</li>
 *   <li>键未在 schema 声明或前缀无法识别 → {@link ConfigFailureReason#UNKNOWN_KEY}</li>
 * </ul>
 */
public class ExternalFileConfigSource implements ConfigSource {

    static final int PRIORITY = 50;
    private static final Logger log = LoggerFactory.getLogger(ExternalFileConfigSource.class);

    private final Path baseDir;
    private final ConfigSchema schema;

    public ExternalFileConfigSource(Path baseDir, ConfigSchema schema) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public String name() {
        return "external-file";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public ConfigSnapshot load(String environment) {
        Objects.requireNonNull(environment, "environment");
        Path file = baseDir.resolve("config-" + environment + ".properties");
        if (!Files.isRegularFile(file)) {
            log.info("[配置来源] 外部文件不存在，按空来源处理: file={}, env={}", file, environment);
            return new ConfigSnapshot(name() + ":" + file.getFileName(), priority(), Map.of());
        }

        List<String> rawLines;
        try {
            rawLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(ConfigFailureReason.INVALID_VALUE,
                    "读取外部配置文件失败: " + file, e);
        }

        Map<String, String> values = new HashMap<>();
        String fileName = file.getFileName().toString();
        int lineNo = 0;
        for (String logical : joinContinuationLines(rawLines)) {
            lineNo++;
            String trimmed = logical.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int sep = firstSeparator(trimmed);
            String rawKey;
            String rawValue;
            if (sep < 0) {
                rawKey = trimmed;
                rawValue = "";
            } else {
                rawKey = trimmed.substring(0, sep).trim();
                rawValue = trimmed.substring(sep + 1).trim();
            }
            String canonical = normalize(rawKey, environment, fileName, lineNo);
            String previous = values.putIfAbsent(canonical, rawValue);
            if (previous != null) {
                throw new ConfigException(ConfigFailureReason.DUPLICATE_KEY,
                        "外部文件内配置键重复定义: key=" + rawKey + ", at=" + fileName + ":" + lineNo);
            }
        }
        log.info("[配置来源] 外部文件加载完成: file={}, env={}, entries={}", file, environment, values.size());
        return new ConfigSnapshot(name() + ":" + fileName, priority(), Map.copyOf(values));
    }

    /**
     * 将文件内的原始键规范化为 scope.name；同时识别跨环境误用与未知键。
     */
    private String normalize(String rawKey, String environment, String fileName, int lineNo) {
        int dot = rawKey.indexOf('.');
        String location = fileName + ":" + lineNo;
        if (dot <= 0) {
            throw new ConfigException(ConfigFailureReason.UNKNOWN_KEY,
                    "未知配置键（缺少环境前缀）: key=" + rawKey + ", at=" + location);
        }
        String prefix = rawKey.substring(0, dot);
        String rest = rawKey.substring(dot + 1);
        if (schema.knownEnvironments().contains(prefix) && !prefix.equals(environment)) {
            throw new ConfigException(ConfigFailureReason.CROSS_ENVIRONMENT,
                    "检测到跨环境配置: 文件环境=" + environment + ", 键前缀环境=" + prefix
                            + ", key=" + rawKey + ", at=" + location);
        }
        if (!prefix.equals(environment)) {
            throw new ConfigException(ConfigFailureReason.UNKNOWN_KEY,
                    "未知配置键（环境前缀无法识别）: key=" + rawKey + ", at=" + location);
        }
        if (schema.find(rest) == null) {
            throw new ConfigException(ConfigFailureReason.UNKNOWN_KEY,
                    "未知配置键: key=" + rawKey + ", at=" + location);
        }
        return rest;
    }

    /** 合并以反斜杠结尾的续行，得到逻辑行集合（保持行号与物理行一致，仅用于错误定位的近似）。 */
    private List<String> joinContinuationLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            boolean cont = line.endsWith("\\");
            buf.append(cont ? line.substring(0, line.length() - 1) : line);
            if (!cont) {
                out.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (!buf.isEmpty()) {
            out.add(buf.toString());
        }
        return out;
    }

    private int firstSeparator(String s) {
        int eq = s.indexOf('=');
        int colon = s.indexOf(':');
        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }
}
