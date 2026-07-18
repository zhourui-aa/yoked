package com.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class ImageAnalyzer {
    private final String apiKey;
    private final String apiUrl;
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageAnalyzer() {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("无法加载 application.properties", e);
        }
        this.apiKey = props.getProperty("ai.api.key");
        this.apiUrl = props.getProperty("ai.api.url");
    }

    public String analyze(byte[] imageBytes, String prompt) {
        try {
            // 1. 图片字节 → Base64 字符串
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            // 2. 拼出 data URL
            String dataUrl = "data:image/jpeg;base64," + base64;

            // 3. 构建 content 数组（两个元素）
            //    第1个元素：图片
            Map<String, Object> imagePart = new LinkedHashMap<>();
            imagePart.put("type", "image_url");
            imagePart.put("image_url", Map.of("url", dataUrl));

            //    第2个元素：文字提示
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            List<Map<String, Object>> content = List.of(imagePart, textPart);

            // 4. 拼完整请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "qwen-vl-max");
            body.put("messages", List.of(
                    Map.of("role", "user", "content", content)
            ));
            body.put("temperature", 0.3);

            String json = objectMapper.writeValueAsString(body);

            // 5. 发 HTTP 请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(45))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // 6. 解析响应（跟文本 API 结构一样）
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").get(0)
                    .path("message").path("content").asText();

        } catch (Exception e) {
            System.err.println("[ImageAnalyzer] 识别失败: " + e.getMessage());
            throw new RuntimeException("图片识别失败: " + e.getMessage(), e);
        }
    }
}
