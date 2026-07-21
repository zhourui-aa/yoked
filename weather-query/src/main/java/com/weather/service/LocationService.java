package com.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 负责定位ip
 */
public class LocationService {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取当前所在城市名（中文）
     * 调用 ip-api.com 免费IP定位服务，不需要API Key
     * @return 城市名，如"北京"；失败返回默认城市"北京"
     */
    public String getCurrentCity() {
        try {
            // ip-api.com 会自动检测请求者的公网IP
            // ?lang=zh-CN 让它返回中文城市名
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json/?lang=zh-CN"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            System.out.println("[LocationService] IP定位响应: " + response.body());

            JsonNode root = objectMapper.readTree(response.body());
            String status = root.path("status").asText();

            if ("success".equals(status)) {
                String city = root.path("city").asText();
                String region = root.path("regionName").asText();
                System.out.println("[LocationService] 定位结果: " + region + " " + city);
                return city;
            } else {
                System.err.println("[LocationService] IP定位失败: " + response.body());
                return "北京";
            }

        } catch (Exception e) {
            System.err.println("[LocationService] 获取位置失败: " + e.getMessage());
            return "北京";
        }
    }
}
