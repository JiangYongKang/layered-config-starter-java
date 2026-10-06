package com.github.highcumontoa.layeredconfigstarterjava.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 系统环境变量配置来源：只读取属于本应用命名空间的环境变量，其余一律忽略。
 *
 * <p>命名约定：以 {@code namespacePrefix}（例如 {@code MYAPP_}）开头的环境变量才属于本应用。
 * 去掉前缀后，剩余部分整体转小写，并将下划线 {@code _} 映射为点 {@code .}：
 * <pre>
 *   MYAPP_APP_PORT  --(prefix=MYAPP_)-->  app.port
 *   MYAPP_APP_DEBUG --(prefix=MYAPP_)-->  app.debug
 * </pre>
 * 不以前缀开头的环境变量（机器上与业务无关的大量系统变量）全部忽略，
 * 既不会报错，也不会作为非法键进入合并与校验。
 *
 * <p>取到的值是普通字符串键值对，与默认值、外部文件来源走完全相同的合并、
 * 类型解析、取值校验与 provenance 记录流程（来源名固定为 {@code env}）。
 */
public final class EnvironmentVariableSource implements ConfigSource {

    private static final String SOURCE_NAME = "env";

    private final String namespacePrefix;
    private final Supplier<Map<String, String>> envSupplier;

    /** 从 {@link System#getenv()} 读取命名空间为 {@code namespacePrefix} 的环境变量。 */
    public EnvironmentVariableSource(String namespacePrefix) {
        this(namespacePrefix, System::getenv);
    }

    /** 可注入环境变量来源的构造器，主要用于测试（不依赖真实机器环境变量）。 */
    public EnvironmentVariableSource(String namespacePrefix, Supplier<Map<String, String>> envSupplier) {
        if (namespacePrefix == null || namespacePrefix.isEmpty()) {
            throw new IllegalArgumentException("namespacePrefix 不能为空");
        }
        this.namespacePrefix = namespacePrefix;
        this.envSupplier = envSupplier;
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> env = envSupplier.get();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String envName = e.getKey();
            if (envName == null || envName.length() <= namespacePrefix.length()) {
                // 不以前缀开头，或去掉前缀后为空 -> 忽略
                continue;
            }
            if (!envName.startsWith(namespacePrefix)) {
                // 约定之外的环境变量：静默忽略，不报错、不进入校验
                continue;
            }
            String configKey = envName.substring(namespacePrefix.length())
                    .toLowerCase(Locale.ROOT)
                    .replace('_', '.');
            if (configKey.isEmpty()) {
                continue;
            }
            result.put(configKey, e.getValue());
        }
        return result;
    }
}
