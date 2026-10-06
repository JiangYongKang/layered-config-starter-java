package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 系统环境变量配置来源。
 *
 * 只识别约定命名空间（前缀）内的变量，前缀之外的环境变量一律忽略——
 * 线上机器上存在大量与业务无关的变量是常态，它们既不报错也不进入配置。
 *
 * 映射规则：以前缀 APP_ 为例，
 *   APP_APP_PORT -> 去掉前缀 APP_PORT -> 转小写 app_port -> '_' 替换为 '.' -> app.port
 *
 * 每次 load() 都重新读取环境变量快照，因此热刷新可以感知到运行期注入的新变量。
 */
public class EnvironmentVariableSource implements ConfigSource {

    private final String prefix;
    private final Supplier<Map<String, String>> envSupplier;

    /** 直接读取当前进程的系统环境变量。 */
    public EnvironmentVariableSource(String prefix) {
        this(prefix, System::getenv);
    }

    /** 可注入环境变量快照来源，便于测试与非本机场景。 */
    public EnvironmentVariableSource(String prefix, Supplier<Map<String, String>> envSupplier) {
        if (prefix == null || prefix.isEmpty()) {
            throw new ConfigException(ConfigException.Reason.ILLEGAL_VALUE, null,
                    "环境变量前缀不能为空，否则会把机器上所有无关变量都当作配置键");
        }
        this.prefix = prefix;
        this.envSupplier = envSupplier;
    }

    @Override
    public String name() {
        return "env(" + prefix + "*)";
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : envSupplier.get().entrySet()) {
            String mapped = mapKey(e.getKey());
            if (mapped != null) {
                result.put(mapped, e.getValue());
            }
        }
        return result;
    }

    /**
     * 将环境变量名映射为配置键；不属于本命名空间的变量返回 null（忽略）。
     */
    private String mapKey(String envName) {
        if (!envName.startsWith(prefix)) {
            return null;
        }
        String stripped = envName.substring(prefix.length());
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.toLowerCase().replace('_', '.');
    }
}
