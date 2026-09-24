package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 外部 properties 文件配置来源。
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
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new ConfigException(ConfigException.Reason.SOURCE_UNAVAILABLE, null,
                    "配置文件读取失败: " + path + " (" + e.getMessage() + ")");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        return map;
    }
}
