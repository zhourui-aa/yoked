package org.example.bot.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.bot.util.ConfigUtil;

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
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 天气查询工具 — 基于 Photon 地理编码 + 和风天气 API。
 * <p>单文件自包含（无外部 model/exception 类依赖），可直接注册为 Function Calling 工具。
 */
public class
WeatherServiceImpl {

    private static final String GEOCODING_URL = "https://photon.komoot.io/api";
    private static final String WEATHER_API_PATH = "/v7/weather/now";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String apiKey;
    private final String weatherApiUrl;
    private final HttpClient httpClient;

    public WeatherServiceImpl(String apiKey, String weatherHost) {
        this.apiKey = apiKey;
        this.weatherApiUrl = "https://" + weatherHost;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** 工厂方法：从 config.properties / 环境变量自动加载配置，失败返回 null */
    public static WeatherServiceImpl create() {
        String key = ConfigUtil.get("qweather.api.key", "QWEATHER_API_KEY");
        String host = ConfigUtil.get("qweather.api.host", "QWEATHER_API_HOST");
        if (key != null && key.startsWith("请在此填入")) key = null;
        if (host != null && host.startsWith("请在此填入")) host = null;
        if (key == null || key.isBlank() || host == null || host.isBlank()) {
            System.out.println("[天气] ⚠ 未配置");
            return null;
        }
        System.out.println("[天气] ✅ 已就绪（Host: " + host + ", Key: " + key.substring(0,8) + "***）");
        return new WeatherServiceImpl(key.strip(), host.strip());
    }

    /** 查询城市天气，返回微信格式文本 */
    public String query(String city) {
        if (city == null || city.isBlank()) return "请告诉我你想查询哪个城市的天气。";
        try {
            // 1. 地理编码
            GeoResult geo = geocode(city.strip());
            // 2. 实时天气
            JsonObject now = fetchWeather(geo.lon, geo.lat);
            // 3. 格式化
            return formatResult(geo.displayName, geo.country,
                    optString(now, "temp", "--"),
                    optString(now, "feelsLike", optString(now, "temp", "--")),
                    optString(now, "text", "未知"),
                    optString(now, "humidity", "--"),
                    optString(now, "windDir", "未知"),
                    optString(now, "windSpeed", "--"));
        } catch (WeatherException e) {
            System.err.println("[天气] ❌ " + e.getMessage());
            return "天气查询失败：" + e.getMessage();
        } catch (Exception e) {
            System.err.println("[天气] ❌ " + e.getMessage());
            e.printStackTrace();
            return "天气服务暂时不可用。";
        }
    }

    // ── 地理编码 ──

    private GeoResult geocode(String city) throws WeatherException {
        String url = GEOCODING_URL + "?q=" + URLEncoder.encode(city, StandardCharsets.UTF_8) + "&limit=1";
        JsonObject root = httpGet(url);
        JsonArray features = root.getAsJsonArray("features");
        if (features == null || features.isEmpty())
            throw new WeatherException("找不到城市 \"" + city + "\"。");
        JsonObject props = features.get(0).getAsJsonObject().getAsJsonObject("properties");
        JsonArray coords = features.get(0).getAsJsonObject().getAsJsonObject("geometry").getAsJsonArray("coordinates");
        double lon = coords.get(0).getAsDouble();
        double lat = coords.get(1).getAsDouble();
        String name = optString(props, "name", city);
        String country = optString(props, "country", "未知");
        String state = optString(props, "state", "");
        String displayName = (!state.isEmpty() && !state.equals(name)) ? name + ", " + state : name;
        return new GeoResult(lon, lat, country, displayName);
    }

    // ── 和风天气 ──

    private JsonObject fetchWeather(double lon, double lat) throws WeatherException {
        String loc = String.format(Locale.US, "%.2f,%.2f", lon, lat);
        String url = weatherApiUrl + WEATHER_API_PATH + "?location="
                + URLEncoder.encode(loc, StandardCharsets.UTF_8) + "&key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        JsonObject root = httpGet(url);
        // 先检查 error 格式的响应
        if (root.has("error")) {
            JsonObject err = root.getAsJsonObject("error");
            throw new WeatherException("API错误(" + optString(err, "status", "?") + "): " + optString(err, "title", "未知"));
        }
        String code = optString(root, "code", "200");
        if (!"200".equals(code))
            throw new WeatherException("API错误(code=" + code + ")");
        JsonObject now = root.getAsJsonObject("now");
        if (now == null || now.isEmpty())
            throw new WeatherException("未获取到天气数据，完整响应见上方日志");
        return now;
    }

    // ── HTTP ──

    private JsonObject httpGet(String url) throws WeatherException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "WeatherBot/1.0")
                .timeout(TIMEOUT).GET().build();
        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new WeatherException("网络错误：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeatherException("请求被中断。");
        }
        byte[] raw = resp.body();
        if (raw == null || raw.length == 0) throw new WeatherException("空响应");
        String body;
        if ((raw.length >= 2 && raw[0] == (byte) 0x1f && raw[1] == (byte) 0x8b)
                || resp.headers().firstValue("Content-Encoding").orElse("").contains("gzip")) {
            try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                body = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new WeatherException("解压失败：" + e.getMessage());
            }
        } else {
            body = new String(raw, StandardCharsets.UTF_8);
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    // ── 格式化 ──

    private String formatResult(String city, String country, String temp, String feels,
                                 String text, String humidity, String windDir, String windSpeed) {
        String emoji = "🌤";
        if (text.contains("雷")) emoji = "⛈";
        else if (text.contains("雨") || text.contains("阵")) emoji = "🌧";
        else if (text.contains("雪")) emoji = "🌨";
        else if (text.contains("雾")) emoji = "🌫";
        else if (text.contains("霾") || text.contains("沙")) emoji = "🌪";
        else if (text.contains("阴")) emoji = "☁️";
        else if (text.contains("多云")) emoji = "⛅";
        else if (text.contains("晴")) emoji = "☀️";
        else if (text.contains("风")) emoji = "💨";
        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append("  ").append(city).append(" 天气\n\n");
        sb.append("🌡 温度：").append(temp).append("°C\n");
        sb.append("🤔 体感：").append(feels).append("°C\n");
        sb.append("☁️ 天气：").append(text).append("\n");
        sb.append("💧 湿度：").append(humidity).append("%\n");
        if (!"未知".equals(windDir) && !"--".equals(windSpeed))
            sb.append("🌬 风速：").append(windDir).append(" ").append(windSpeed).append(" km/h");
        return sb.toString();
    }

    // ── 工具 ──

    private String optString(JsonObject obj, String key, String def) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) return def;
        try { return e.getAsString(); } catch (Exception ex) { return def; }
    }

    private record GeoResult(double lon, double lat, String country, String displayName) {}

    private static class WeatherException extends Exception {
        WeatherException(String msg) { super(msg); }
    }
}
