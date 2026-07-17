package com.weather.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.weather.exception.WeatherException;
import com.weather.model.WeatherData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 天气查询服务 — 组合 Open-Meteo（地理编码）+ 和风天气（实时天气）。
 *
 * <h3>调用流程（两步）</h3>
 * <ol>
 *   <li><b>城市搜索</b>：Open-Meteo Geocoding API（免费，无需 Key）
 *       → 获取经纬度、国家、城市名</li>
 *   <li><b>实时天气</b>：和风天气 API（需要 Key + 专属 Host）
 *       → 传入 {@code lon,lat} 获取当前天气</li>
 * </ol>
 */
public class WeatherService {

    /** Open-Meteo 地理编码（免费，无需 API Key） */
    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String WEATHER_API_PATH = "/v7/weather/now";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 和风天气返回的错误码 → 中文消息 */
    private static final java.util.Map<String, String> ERROR_CODES = java.util.Map.ofEntries(
            java.util.Map.entry("204", "请求成功，但该地区暂无数据。"),
            java.util.Map.entry("400", "请求参数错误。"),
            java.util.Map.entry("401", "API Key 无效，请检查 config.properties 中的密钥。"),
            java.util.Map.entry("402", "API 调用次数已用完，请等待次日重置。"),
            java.util.Map.entry("403", "无权限访问此 API，请检查项目订阅。"),
            java.util.Map.entry("404", "未找到该城市的天气数据。"),
            java.util.Map.entry("429", "请求过于频繁，请稍后重试。"),
            java.util.Map.entry("500", "和风天气服务器内部错误，请稍后重试。")
    );

    private final String apiKey;
    private final String weatherApiUrl;
    private final HttpClient httpClient;

    public WeatherService(String apiKey, String weatherHost) {
        this.apiKey = apiKey;
        this.weatherApiUrl = "https://" + weatherHost;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ---- 公开接口 ---------------------------------------------------------------

    /**
     * 查询指定城市的实时天气。
     *
     * @param city 城市名，支持中文（"北京"）、英文（"Beijing"）
     * @return 填充好的 {@link WeatherData}
     * @throws WeatherException 城市名为空、API Key 无效、城市不存在、网络故障等
     */
    public WeatherData query(String city) throws WeatherException {
        // --- 1. 校验 API Key ---
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("请在此填入")) {
            throw new WeatherException("API Key 未配置。请在 config.properties 中填入和风天气 API Key。\n"
                    + "  获取方式：访问 https://dev.qweather.com/ 注册并创建项目。");
        }

        // --- 2. 校验 API Host ---
        if (weatherApiUrl == null || weatherApiUrl.contains("请在此填入")
                || weatherApiUrl.contains("未配置")) {
            throw new WeatherException("API Host 未配置。请在 config.properties 中填入你的专属 API Host。\n"
                    + "  获取方式：登录 https://console.qweather.com/setting 复制你的 API Host。");
        }

        // --- 3. 校验输入 ---
        if (city == null || city.isBlank()) {
            throw new WeatherException("城市名不能为空。用法：weather <城市名>");
        }

        String trimmed = city.strip();
        log("[信息] 正在查询: " + trimmed);

        // --- 4. 第一步：Open-Meteo 地理编码 → 获取坐标 + 国家 ---
        GeoResult geo = geocode(trimmed);

        // --- 5. 第二步：和风天气实时天气 ---
        JsonObject now = fetchWeather(geo.lon, geo.lat);

        // --- 6. 组装结果 ---
        String tempC     = optString(now, "temp", "--");
        String feelsLike = optString(now, "feelsLike", tempC);
        String text      = optString(now, "text", "未知");
        String humidity  = optString(now, "humidity", "--");
        String windDir   = optString(now, "windDir", "未知");
        String windSpeed = optString(now, "windSpeed", "--");

        return new WeatherData(geo.displayName, geo.country,
                parseDoubleSafe(tempC), parseDoubleSafe(feelsLike),
                parseIntSafe(humidity), text,
                parseDoubleSafe(windSpeed), windDir);
    }

    // ---- 地理编码：Open-Meteo（免费，无需 Key）----------------------------------

    /**
     * 调用 Open-Meteo Geocoding API，返回经纬度 + 国家。
     */
    private GeoResult geocode(String city) throws WeatherException {
        // Open-Meteo 支持中文城市名
        String url = GEOCODING_URL + "?name=" + encode(city)
                + "&count=1&language=zh&format=json";
        log("[调试] 地理编码: " + url);

        JsonObject root = httpGet(url);

        JsonArray results = root.getAsJsonArray("results");
        if (results == null || results.size() == 0) {
            throw new WeatherException("找不到城市 \"" + city + "\"，请检查拼写是否正确。");
        }

        JsonObject r = results.get(0).getAsJsonObject();
        double lat = r.get("latitude").getAsDouble();
        double lon = r.get("longitude").getAsDouble();
        String name = optString(r, "name", city);
        String country = optString(r, "country", "未知");
        String admin1 = optString(r, "admin1", "");

        String displayName = name;
        if (!admin1.isEmpty() && !admin1.equals(name)) {
            displayName = name + ", " + admin1;
        }

        log("[信息] 找到: " + displayName + ", " + country
                + " (坐标: " + lon + ", " + lat + ")");

        return new GeoResult(lon, lat, country, displayName);
    }

    // ---- 实时天气查询：和风天气 --------------------------------------------------

    /**
     * 调用和风天气实时天气 API（使用经纬度）。
     */
    private JsonObject fetchWeather(double lon, double lat) throws WeatherException {
        // 和风天气接受 location=lon,lat 格式
        String location = String.format(Locale.US, "%.2f,%.2f", lon, lat);
        String url = weatherApiUrl + WEATHER_API_PATH
                + "?location=" + encode(location) + "&key=" + encode(apiKey);
        log("[调试] 天气查询: " + url);

        JsonObject root = httpGet(url);
        checkApiCode(root, location);

        JsonObject now = root.getAsJsonObject("now");
        if (now == null || now.size() == 0) {
            throw new WeatherException("未获取到该城市的天气数据。");
        }

        return now;
    }

    // ---- HTTP GET 基础方法 -------------------------------------------------------

    /**
     * 发起 GET 请求，返回解析后的 JSON 对象。自动处理 gzip 解压。
     */
    private JsonObject httpGet(String url) throws WeatherException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "WeatherCLI/1.0")
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new WeatherException("网络错误：无法连接到服务 — " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeatherException("请求被中断。", e);
        }

        int status = response.statusCode();
        byte[] rawBody = response.body();
        log("[调试] HTTP " + status + ", 响应长度: " + (rawBody != null ? rawBody.length : 0) + " 字节");

        if (status != 200) {
            throw new WeatherException("服务返回 HTTP " + status + "，请稍后重试。");
        }

        if (rawBody == null || rawBody.length == 0) {
            throw new WeatherException("服务返回了空响应。");
        }

        // 检查是否需要 gzip 解压
        String body;
        String encoding = response.headers().firstValue("Content-Encoding").orElse("");
        if (encoding.contains("gzip") || isGzipData(rawBody)) {
            try (InputStream gzis = new GZIPInputStream(new ByteArrayInputStream(rawBody))) {
                body = new String(gzis.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new WeatherException("解压响应数据失败：" + e.getMessage(), e);
            }
        } else {
            body = new String(rawBody, StandardCharsets.UTF_8);
        }

        log("[调试] 响应内容: " + (body.length() > 200 ? body.substring(0, 200) : body));

        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new WeatherException("解析响应数据失败：" + e.getMessage(), e);
        }
    }

    /** 检测是否为 gzip 压缩数据（gzip 魔数: 1f 8b） */
    private static boolean isGzipData(byte[] data) {
        return data.length >= 2 && data[0] == (byte) 0x1f && data[1] == (byte) 0x8b;
    }

    /**
     * 检查和风天气 API 统一错误码。code 为 "200" 表示成功。
     */
    private void checkApiCode(JsonObject root, String context) throws WeatherException {
        String code = optString(root, "code", "200");
        if ("200".equals(code)) return;

        String msg = ERROR_CODES.getOrDefault(code, "未知错误 (code=" + code + ")");
        throw new WeatherException("「" + context + "」查询失败 — " + msg);
    }

    // ---- 内部数据类 -------------------------------------------------------------

    /** 地理编码结果 */
    private static class GeoResult {
        final double lon, lat;
        final String country;
        final String displayName;

        GeoResult(double lon, double lat, String country, String displayName) {
            this.lon = lon;
            this.lat = lat;
            this.country = country;
            this.displayName = displayName;
        }
    }

    // ---- 安全的 JSON 取值 -------------------------------------------------------

    private String optString(JsonObject obj, String key, String defaultVal) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return defaultVal;
        try {
            return el.getAsString();
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); }
        catch (Exception e) { return 0; }
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); }
        catch (Exception e) { return 0; }
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- 日志 -------------------------------------------------------------------

    private void log(String msg) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        System.out.println("[" + ts + "] " + msg);
    }
}
