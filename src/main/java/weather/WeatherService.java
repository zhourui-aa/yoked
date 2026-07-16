package weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class WeatherService {
    private static final String API_KEY = "d9315d971f0d47a69cd2406a4ab14534";
    private static final String API_HOST = "kx3jpj749m.re.qweatherapi.com";

    private final OkHttpClient httpClient;

    public WeatherService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public WeatherInfo getCurrentWeather(String cityName) throws WeatherException {
        System.out.println("🔍 开始查询天气: " + cityName);

        try {
            System.out.println("🌐 搜索城市ID...");
            String cityId = searchCity(cityName);
            System.out.println("✅ 城市ID: " + cityId);

            String url = "https://" + API_HOST + "/v7/weather/now?location=" + cityId + "&key=" + API_KEY;
            System.out.println("🌐 请求天气数据...");

            JsonObject data = httpGet(url);
            System.out.println("✅ 收到天气数据");

            // 检查 "now" 字段
            if (!data.has("now")) {
                System.err.println("❌ 响应中没有 'now' 字段");
                System.err.println("响应内容: " + data.toString());
                throw new WeatherException("API响应格式错误：缺少now字段");
            }

            JsonObject now = data.getAsJsonObject("now");
            System.out.println("✅ 解析 now 对象成功");

            WeatherInfo info = new WeatherInfo();
            info.setCity(cityName);
            info.setTemperature(getString(now, "temp") + "°C");
            info.setFeelsLike(getString(now, "feelsLike") + "°C");
            info.setCondition(getString(now, "text"));
            info.setWindDirection(getString(now, "windDir"));
            info.setWindScale(getString(now, "windScale") + "级");
            info.setHumidity(getString(now, "humidity") + "%");
            info.setPressure(getString(now, "pressure") + "hPa");
            info.setVisibility(getString(now, "vis") + "km");
            info.setPrecipitation(getString(now, "precip") + "mm");
            info.setUpdateTime(getString(data, "updateTime"));

            System.out.println("✅ 天气信息组装完成");
            return info;

        } catch (IOException e) {
            System.err.println("❌ IO错误: " + e.getMessage());
            e.printStackTrace();
            throw new WeatherException("网络错误: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("❌ 未知错误: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            throw new WeatherException("处理错误: " + e.getMessage());
        }
    }

    private String searchCity(String cityName) throws IOException, WeatherException {
        String url = "https://" + API_HOST + "/geo/v2/city/lookup?location=" + cityName + "&key=" + API_KEY + "&range=cn";
        System.out.println("🌐 城市搜索URL: " + url);

        JsonObject data = httpGet(url);
        System.out.println("📡 城市搜索响应: " + data.toString().substring(0, Math.min(200, data.toString().length())) + "...");

        // 检查 location 字段
        if (!data.has("location")) {
            System.err.println("❌ 响应中没有 'location' 字段");
            System.err.println("完整响应: " + data.toString());
            throw new WeatherException("API响应格式错误：缺少location字段");
        }

        JsonArray locations = data.getAsJsonArray("location");
        System.out.println("📡 location 数组大小: " + (locations != null ? locations.size() : "null"));

        if (locations == null || locations.size() == 0) {
            throw new WeatherException("未找到城市: " + cityName);
        }

        String cityId = locations.get(0).getAsJsonObject().get("id").getAsString();
        System.out.println("✅ 找到城市ID: " + cityId);
        return cityId;
    }

    private JsonObject httpGet(String url) throws IOException, WeatherException {
        System.out.println("📡 HTTP GET: " + url.substring(0, Math.min(100, url.length())) + "...");

        Request request = new Request.Builder()
                .url(url)
                // 不请求 Gzip，直接返回纯文本 JSON
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            System.out.println("📡 HTTP 状态码: " + response.code());

            if (!response.isSuccessful()) {
                throw new WeatherException("HTTP错误: " + response.code());
            }

            String body = response.body().string();
            System.out.println("📡 响应体: " + body.substring(0, Math.min(300, body.length())) + "...");

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            System.out.println("✅ JSON解析成功");

            String code = getString(json, "code");
            System.out.println("📡 API返回码: " + code);

            if (!"200".equals(code) && !"204".equals(code)) {
                throw new WeatherException("API错误: code=" + code);
            }

            return json;
        }
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null) return "N/A";
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "N/A";
    }

    public static class WeatherException extends Exception {
        public WeatherException(String message) {
            super(message);
        }
    }
}