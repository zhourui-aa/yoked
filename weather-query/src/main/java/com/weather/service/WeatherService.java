package com.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weather.config.ApiConfig;
import com.weather.exception.WeatherException;
import com.weather.model.WeatherResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * 天气查询核心服务
 *
 * 流程：城市名 → 城市搜索API(拿locationId) → 实时天气API(拿天气) → 解析返回
 */
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /** HTTP 客户端（全局复用，不要每次 new） */
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** JSON 解析器（线程安全，全局复用） */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ApiConfig config;

    public WeatherService() {
        this.config = new   ApiConfig();
        log.info("WeatherService 初始化完成");
    }

    /**
     * 根据城市名查询实时天气
     *
     * @param cityName 城市中文名，例如 "北京"、"上海"
     * @return 天气响应对象
     * @throws WeatherException 城市名为空、找不到城市、API调用失败等
     */
    public WeatherResponse queryByCity(String cityName) {
        // ====== 第一步：参数校验 ======
        if (cityName == null || cityName.isBlank()) {
            log.warn("城市名参数为空");
            throw new WeatherException("城市名不能为空，请重新输入。");
        }

        String trimmed = cityName.trim();
        log.info("开始查询城市天气: [{}]", trimmed);

        // ====== 第二步：城市搜索 → 拿到 locationId ======
        String locationId = fetchLocationId(trimmed);//根据城市名调用地理搜索 API，拿到城市唯一 locationId

        // ====== 第三步：用 locationId 查询天气 ======
        String weatherJson = fetchWeatherJson(locationId);//用城市 ID 调用实时天气接口，获取压缩 JSON 字符串

        // ====== 第四步：解析 JSON 为 Java 对象 ======
        WeatherResponse response;
        try {
            response = objectMapper.readValue(weatherJson, WeatherResponse.class);
        } catch (Exception e) {
            log.error("JSON 解析失败: {}", weatherJson, e);
            throw new WeatherException("天气数据解析失败，请稍后重试", e);
        }

        // 校验 API 返回状态码
        if (!response.isSuccess()) {
            log.error("API 返回异常状态码: {}", response.getCode());
            throw new WeatherException("天气查询失败，API 返回状态码: " + response.getCode());
        }

        response.setCityName(trimmed);
        log.info("天气查询成功: {} - {}°C, {}", trimmed,
                response.getNow().getTemp(), response.getNow().getText());
        return response;
    }

    /**
     * 通过城市名搜索获取 locationId
     */
    private String fetchLocationId(String cityName) {
        String url = config.getGeoUrl() + "?location="
                + URLEncoder.encode(cityName, StandardCharsets.UTF_8)
                + "&key=" + config.getApiKey();

        log.debug("城市搜索请求: {}", url);

        String body = sendGetRequest(url);

        try {
            JsonNode root = objectMapper.readTree(body);
            String code = root.path("code").asText();

            if (!"200".equals(code)) {
                log.error("城市搜索失败，API code={}", code);
                throw new WeatherException("找不到城市 [" + cityName + "]，请检查城市名拼写。");
            }

            JsonNode locationArray = root.path("location");
            if (!locationArray.isArray() || locationArray.isEmpty()) {
                log.error("城市搜索无结果: {}", cityName);
                throw new WeatherException("找不到城市 [" + cityName + "]，请检查城市名拼写。");
            }

            String id   = locationArray.get(0).path("id").asText();
            String name = locationArray.get(0).path("name").asText();
            log.info("城市搜索成功: {} → id={}", name, id);
            return id;

        } catch (WeatherException e) {
            throw e;  // 原样抛出业务异常
        } catch (Exception e) {
            log.error("城市搜索响应解析失败: {}", body, e);
            throw new WeatherException("城市搜索数据解析失败", e);
        }
    }

    /**
     * 通过 locationId 获取实时天气 JSON
     */
    private String fetchWeatherJson(String locationId) {
        String url = config.getWeatherUrl() + "?location="
                + locationId + "&key=" + config.getApiKey();

        log.debug("天气请求: {}", url);
        return sendGetRequest(url);
    }

    /**
     * 通用 GET 请求方法
     */
    private String sendGetRequest(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status != 200) {
                log.error("HTTP 请求失败, status={}, url={}", status, url);
                throw new WeatherException("HTTP 请求失败, 状态码: " + status);
            }

            // 和风天气 API 默认返回 gzip 压缩，需要解压
            InputStream inputStream = response.body();
            String contentEncoding = response.headers()
                    .firstValue("Content-Encoding").orElse("");
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                inputStream = new GZIPInputStream(inputStream);
            }

            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

        } catch (WeatherException e) {
            throw e;
        } catch (Exception e) {
            log.error("网络请求异常, url={}", url, e);
            throw new WeatherException("网络请求失败，请检查网络连接后重试", e);
        }
    }
}
