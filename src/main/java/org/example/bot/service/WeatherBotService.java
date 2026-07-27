package org.example.bot.service;

import com.weather.exception.WeatherException;
import com.weather.service.WeatherService;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 天气查询服务门面 — 封装和风天气 API。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * WeatherBotService weather = WeatherBotService.create();
 * String result = weather.query("北京");
 * }</pre>
 */
public class WeatherBotService {

    private final WeatherService weatherService;

    private WeatherBotService(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /**
     * 创建天气服务实例，自动从 config.properties 加载配置。
     *
     * @return 可用的实例，如果配置缺失则返回 {@code null}
     */
    public static WeatherBotService create() {
        String apiKey = null;
        String weatherHost = null;

        apiKey = System.getProperty("qweather.api.key");
        weatherHost = System.getProperty("qweather.api.host");

        if (isBlank(apiKey))  apiKey = System.getenv("QWEATHER_API_KEY");
        if (isBlank(weatherHost)) weatherHost = System.getenv("QWEATHER_API_HOST");

        Path configPath = Paths.get("config.properties");
        if (Files.exists(configPath)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                if (isBlank(apiKey)) {
                    String key = props.getProperty("qweather.api.key");
                    if (key != null && !key.startsWith("请在此填入")) apiKey = key.strip();
                }
                if (isBlank(weatherHost)) {
                    String host = props.getProperty("qweather.api.host");
                    if (host != null && !host.startsWith("请在此填入")) weatherHost = host.strip();
                }
            } catch (IOException ignored) {}
        }

        if (isBlank(apiKey) || isBlank(weatherHost)) {
            System.out.println("[天气] ⚠ 天气服务未配置");
            return null;
        }

        System.out.println("[天气] ✅ 天气服务已就绪（Host: " + weatherHost + "）");
        return new WeatherBotService(new WeatherService(apiKey.strip(), weatherHost.strip()));
    }

    /**
     * 查询城市实时天气。
     *
     * @param city 城市名，支持中文和英文
     * @return 格式化的天气信息
     */
    public String query(String city) {
        if (city == null || city.isBlank()) {
            return "请告诉我你想查询哪个城市的天气，例如：天气 北京";
        }
        try {
            return weatherService.query(city.strip()).toWeChatFormat();
        } catch (WeatherException e) {
            return "天气查询失败：" + e.getMessage();
        } catch (Exception e) {
            System.err.println("[天气] ❌ 查询异常: " + e.getMessage());
            return "抱歉，天气服务暂时不可用，请稍后再试。";
        }
    }

    public boolean isAvailable() {
        return weatherService != null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
