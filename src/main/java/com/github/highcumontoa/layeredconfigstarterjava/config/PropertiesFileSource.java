package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 properties 文件配置来源。
 *
 * 不再使用 java.util.Properties（它对重复键静默取最后一条），
 * 而是自行解析：同一个键在文件中出现多次时，抛出
 * {@link ConfigException.Reason#DUPLICATE_KEY_IN_SOURCE}，
 * 让配置错误被明确识别，而不是悄悄用最后一个值继续跑。
 */
public class PropertiesFileSource implements ConfigSource {

    private final Path path;

    public PropertiesFileSource(Path path) {
        this.path = path;
    }

    @Override
    public String name() {
        return "file:" + path;
    }

    @Override
    public Map<String, String> load() {
        if (!Files.exists(path)) {
            throw new ConfigException(ConfigException.Reason.SOURCE_UNAVAILABLE, null,
                    "配置文件不存在: " + path);
        }
        List<String> lines;
        try {
            // 与 java.util.Properties 一致，按 ISO-8859-1 读取
            lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new ConfigException(ConfigException.Reason.SOURCE_UNAVAILABLE, null,
                    "配置文件读取失败: " + path + " (" + e.getMessage() + ")");
        }
        return parse(lines);
    }

    private Map<String, String> parse(List<String> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        Map<String, Integer> firstSeenLine = new LinkedHashMap<>();
        List<String> logicalLines = joinContinuations(lines);
        int lineNo = 0;
        for (String raw : logicalLines) {
            lineNo++;
            String line = raw.stripLeading();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') {
                continue;
            }
            String[] kv = splitKeyValue(line);
            String key = kv[0];
            String value = kv[1];
            Integer first = firstSeenLine.get(key);
            if (first != null) {
                throw new ConfigException(ConfigException.Reason.DUPLICATE_KEY_IN_SOURCE, key,
                        "配置文件 " + path + " 中键 [" + key + "] 被重复定义（第 " + first
                                + " 条与第 " + lineNo + " 条逻辑行），请只保留一处；"
                                + "不会静默采用最后一条值");
            }
            firstSeenLine.put(key, lineNo);
            map.put(key, value);
        }
        return map;
    }

    /** 处理反斜杠续行：奇数个尾随反斜杠表示与下一行拼接。 */
    private static List<String> joinContinuations(List<String> lines) {
        List<String> logical = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean continuing = false;
        for (String line : lines) {
            // 续行时跳过下一行的前导空白（与 java.util.Properties 行为一致）
            current.append(continuing ? line.stripLeading() : line);
            if (endsWithOddBackslashes(current)) {
                current.setLength(current.length() - 1);
                continuing = true;
            } else {
                logical.add(current.toString());
                current.setLength(0);
                continuing = false;
            }
        }
        if (continuing || current.length() > 0) {
            logical.add(current.toString());
        }
        return logical;
    }

    private static boolean endsWithOddBackslashes(CharSequence s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            count++;
        }
        return count % 2 == 1;
    }

    /** 按 properties 规则切分键值：第一个未转义的 '='、':' 或空白为分隔符。 */
    private static String[] splitKeyValue(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++; // 跳过转义字符
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                String key = line.substring(0, i);
                String rest = line.substring(i);
                // 跳过分隔符及其后紧跟的 '='、':' 与空白
                int j = 0;
                boolean sepSeen = false;
                while (j < rest.length()) {
                    char r = rest.charAt(j);
                    if (Character.isWhitespace(r)
                            || (!sepSeen && (r == '=' || r == ':'))) {
                        if (r == '=' || r == ':') {
                            sepSeen = true;
                        }
                        j++;
                    } else {
                        break;
                    }
                }
                return new String[]{unescape(key), unescape(rest.substring(j))};
            }
        }
        return new String[]{unescape(line), ""};
    }

    /** 处理 properties 常见转义（\t \n \r \\ \= \: 等）。 */
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 't' -> out.append('\t');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 'f' -> out.append('\f');
                    default -> out.append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
