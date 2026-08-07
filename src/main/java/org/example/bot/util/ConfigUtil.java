package org.example.bot.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 配置读取工具 — 按优先级查找配置值。
 *
 * <p>优先级：系统属性 &gt; 环境变量 &gt; config.properties 文件
 */
public final class ConfigUtil {

    private ConfigUtil() {}

    /** 缓存 config.properties，避免每次 get() 都读盘 */
    private static volatile Properties cachedProps;
    private static volatile boolean propsLoaded;

    private static Properties loadProps() {
        if (propsLoaded) return cachedProps;
        synchronized (ConfigUtil.class) {
            if (propsLoaded) return cachedProps;
            Path configPath = Paths.get("config.properties");
            if (Files.exists(configPath)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(configPath)) {
                    props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                    cachedProps = props;
                    propsLoaded = true; // 仅在成功加载后标记，避免负缓存
                } catch (Exception e) {
                    System.err.println("[Config] 读取 config.properties 失败: " + e.getMessage());
                }
            }
            return cachedProps;
        }
    }

    /**
     * 读取配置值。
     *
     * <p>查找顺序：
     * <ol>
     *   <li>System.getProperty({@code propertyKey}) — 例如 {@code -Ddeepseek.api.key=xxx}</li>
     *   <li>System.getenv({@code envKey}) — 例如 {@code DEEPSEEK_API_KEY}</li>
     *   <li>项目根目录 config.properties 中的 {@code propertyKey}</li>
     * </ol>
     *
     * @param propertyKey config.properties 中的键名（如 "deepseek.api.key"）
     * @param envKey      环境变量名（如 "DEEPSEEK_API_KEY"）
     * @return 配置值，都未配置时返回 {@code null}
     */
    public static String get(String propertyKey, String envKey) {
        // 1. 系统属性（-D 参数）
        String value = System.getProperty(propertyKey);
        if (value != null && !value.isBlank()) {
            return value.strip();
        }

        // 2. 环境变量
        value = System.getenv(envKey);
        if (value != null && !value.isBlank()) {
            return value.strip();
        }

        // 3. config.properties（缓存读取，避免每次读盘）
        Properties props = loadProps();
        if (props != null) {
            value = props.getProperty(propertyKey);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }

        return null;
    }
}
