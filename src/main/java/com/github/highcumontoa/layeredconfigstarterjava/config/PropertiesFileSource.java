package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部 properties 文件配置来源。
 *
 * <p>与直接使用 {@link java.util.Properties#load} 不同，这里自行逐行解析，
 * 以便在同一个键在文件中被书写两次或更多次时明确报
 * {@link ConfigException.Reason#DUPLICATE_DEFINITION}，而不是静默采用最后一个值。
 *
 * <p>支持标准 properties 语法的常用子集：
 * 注释（{@code #}/{@code !} 开头）、空白行、反斜杠续行、
 * 分隔符 {@code =} / {@code :} / 空白、以及键值中的转义
 * （{@code &#92;=} {@code &#92;:} {@code &#92; } {@code &#92;&#92;}
 * {@code &#92;n} {@code &#92;t} {@code &#92;r} 与反斜杠开头的四位十六进制 unicode 转义）。
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
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(ConfigException.Reason.SOURCE_UNAVAILABLE, null,
                    "配置文件读取失败: " + path + " (" + e.getMessage() + ")");
        }
        return parse(content);
    }

    private Map<String, String> parse(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        // 记录每个键第一次出现的行号，便于定位重复
        Map<String, Integer> firstLine = new LinkedHashMap<>();

        int index = 0;
        int length = content.length();
        int lineNo = 1;

        while (index < length) {
            // 跳过行首空白
            while (index < length && isHorizontalWhitespace(content.charAt(index))) {
                index++;
            }
            // 跳过整行注释
            if (index < length && (content.charAt(index) == '#' || content.charAt(index) == '!')) {
                index = skipToNextLine(content, index, length);
                lineNo++;
                continue;
            }
            if (index >= length) {
                break;
            }
            // 空行（只剩行终止符）
            if (isLineTerminator(content, index, length)) {
                if (content.charAt(index) == '\r' && index + 1 < length && content.charAt(index + 1) == '\n') {
                    index += 2;
                } else {
                    index++;
                }
                lineNo++;
                continue;
            }

            int entryStartLine = lineNo;

            // 解析键：到第一个未转义的分隔符（=、:、空白）为止；支持反斜杠转义与续行
            StringBuilder rawKey = new StringBuilder();
            boolean terminatedBySep = false;
            while (index < length) {
                char c = content.charAt(index);
                if (c == '\\' && index + 1 < length) {
                    char next = content.charAt(index + 1);
                    if (next == '\n' || (next == '\r'
                            && (index + 2 >= length || content.charAt(index + 2) == '\n'))) {
                        // 行续接：键中的转义换行 -> 直接去掉，行数计入
                        index += (next == '\r') ? 3 : 2;
                        lineNo++;
                        continue;
                    }
                    rawKey.append(c).append(next);
                    index += 2;
                    continue;
                }
                if (c == '=' || c == ':' || isHorizontalWhitespace(c)) {
                    terminatedBySep = true;
                    break;
                }
                if (c == '\n' || c == '\r') {
                    break;
                }
                rawKey.append(c);
                index++;
            }

            // 跳过分隔符（含分隔符两侧的空白）
            if (terminatedBySep) {
                while (index < length && isHorizontalWhitespace(content.charAt(index))) {
                    index++;
                }
                if (index < length && (content.charAt(index) == '=' || content.charAt(index) == ':')) {
                    index++;
                    while (index < length && isHorizontalWhitespace(content.charAt(index))) {
                        index++;
                    }
                }
            }

            // 解析值：到未转义的行尾；支持转义与续行
            StringBuilder rawValue = new StringBuilder();
            while (index < length) {
                char c = content.charAt(index);
                if (c == '\\' && index + 1 < length) {
                    char next = content.charAt(index + 1);
                    if (next == '\n' || (next == '\r'
                            && (index + 2 >= length || content.charAt(index + 2) == '\n'))) {
                        index += (next == '\r') ? 3 : 2;
                        lineNo++;
                        // 续行后的前导空白按 properties 惯例忽略
                        while (index < length && isHorizontalWhitespace(content.charAt(index))) {
                            index++;
                        }
                        continue;
                    }
                    rawValue.append(c).append(next);
                    index += 2;
                    continue;
                }
                if (c == '\n' || c == '\r') {
                    break;
                }
                rawValue.append(c);
                index++;
            }

            String key = unescape(rawKey.toString().strip());
            String value = unescape(rawValue.toString().strip());

            if (!key.isEmpty()) {
                Integer first = firstLine.get(key);
                if (first != null) {
                    throw new ConfigException(ConfigException.Reason.DUPLICATE_DEFINITION, key,
                            "配置文件 [" + path + "] 中键 [" + key + "] 被重复定义（首次出现于第 "
                                    + first + " 行，再次出现于第 " + entryStartLine + " 行），"
                                    + "请删除重复项；组件不会静默采用最后一个值");
                }
                firstLine.put(key, entryStartLine);
                result.put(key, value);
            }

            // 移动到下一行开头
            if (index < length && isLineTerminator(content, index, length)) {
                if (content.charAt(index) == '\r' && index + 1 < length && content.charAt(index + 1) == '\n') {
                    index += 2;
                } else {
                    index++;
                }
                lineNo++;
            }
        }
        return result;
    }

    private static int skipToNextLine(String content, int index, int length) {
        while (index < length && !isLineTerminator(content, index, length)) {
            index++;
        }
        if (index < length) {
            index += (content.charAt(index) == '\r' && index + 1 < length
                    && content.charAt(index + 1) == '\n') ? 2 : 1;
        }
        return index;
    }

    private static boolean isLineTerminator(String content, int index, int length) {
        char c = content.charAt(index);
        return c == '\n' || c == '\r';
    }

    private static boolean isHorizontalWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    /** 解析 properties 转义；未知转义保留反斜杠后的字符本身。 */
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            out.append('\\').append(n);
                        }
                    } else {
                        out.append('\\').append(n);
                    }
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }
}
