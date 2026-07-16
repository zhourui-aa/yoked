package com.weather.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * API 配置读取器
 * 从 application.properties 加载和风天气的 Key 和 URL
 */
public class ApiConfig {

    private final String apiKey;
    private final String geoUrl;
    private final String weatherUrl;

    private static final String CONFIG_FILE = "/application.properties";

    public ApiConfig() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                throw new RuntimeException(
                        "找不到配置文件: " + CONFIG_FILE + "，请确认文件存在于 src/main/resources/ 下");
            }
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("读取配置文件失败: " + CONFIG_FILE, e);
        }

        this.apiKey     = props.getProperty("weather.api.key", "");
        this.geoUrl     = props.getProperty("weather.api.geo-url", "");
        this.weatherUrl = props.getProperty("weather.api.weather-url", "");

        if (apiKey.isBlank() || "YOUR_API_KEY_HERE".equals(apiKey)) {
            throw new RuntimeException(
                    "API Key 未配置！请修改 src/main/resources/application.properties 中的 weather.api.key");
        }
    }

    public String getApiKey()       { return apiKey; }
    public String getGeoUrl()       { return geoUrl; }
    public String getWeatherUrl()   { return weatherUrl; }
}