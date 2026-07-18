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
import java.util.Map;
import java.util.Properties;

public class ImageGenerator {
    private final String apiKey;
    private final String imageGenUrl;
    private final String taskQueryUrl;

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageGenerator() {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("无法加载 application.properties", e);
        }
        this.apiKey = props.getProperty("ai.api.key");
        this.imageGenUrl = props.getProperty("image.gen.url", "");
        this.taskQueryUrl = props.getProperty("task.query.url", "");

        if (imageGenUrl.isEmpty() || taskQueryUrl.isEmpty()) {
            throw new RuntimeException(
                    "图片生成 URL 未配置！请检查 application.properties 中 image.gen.url 和 task.query.url");
        }
    }

    public byte[] generate(String prompt) {
        try {
            // ===== 第一步：提交生成任务 =====
            Map<String, Object> input = Map.of("prompt", prompt);
            Map<String, Object> params = Map.of("size", "1024*1024", "n", 1);
            Map<String, Object> body = Map.of(
                    "model", "wanx-v1",
                    "input", input,
                    "parameters", params
            );

            String json = objectMapper.writeValueAsString(body);

            HttpRequest genRequest = HttpRequest.newBuilder()
                    .uri(URI.create(imageGenUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-DashScope-Async", "enable")   // ← 异步模式
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> genResponse = httpClient.send(genRequest,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode genRoot = objectMapper.readTree(genResponse.body());

            // 检查是否同步返回了结果（小概率）
            JsonNode results = genRoot.path("output").path("results");
            if (results.isArray() && results.size() > 0) {
                String imageUrl = results.get(0).path("url").asText();
                if (!imageUrl.isEmpty()) {
                    return downloadImage(imageUrl);
                }
            }

            // ===== 第二步：拿到 taskId =====
            String taskId = genRoot.path("output").path("task_id").asText();
            if (taskId.isEmpty()) {
                String errorMsg = genRoot.path("output").path("message").asText();
                if (errorMsg.isEmpty()) {
                    errorMsg = genRoot.path("message").asText();
                }
                System.err.println("[ImageGenerator] 提交失败！");
                System.err.println("  响应: " + genResponse.body());
                throw new RuntimeException("提交图片生成任务失败: " + errorMsg);
            }

            System.out.println("[ImageGenerator] 提交图片生成任务，prompt: " + prompt);
            System.out.println("[ImageGenerator] 任务ID: " + taskId + "，开始轮询...");
            // ===== 第三步：轮询等结果（最多等30秒） =====
            for (int i = 0; i < 60; i++) {
                Thread.sleep(2000);

                HttpRequest pollRequest = HttpRequest.newBuilder()
                        .uri(URI.create(taskQueryUrl + taskId))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> pollResp = httpClient.send(pollRequest,
                        HttpResponse.BodyHandlers.ofString());
                JsonNode taskRoot = objectMapper.readTree(pollResp.body());

                String status = taskRoot.path("output").path("task_status").asText();
                System.out.println("[ImageGenerator] 任务状态: " + status);

                if ("SUCCEEDED".equals(status)) {
                    String imageUrl = taskRoot.path("output").path("results")
                            .get(0).path("url").asText();
                    return downloadImage(imageUrl);

                } else if ("FAILED".equals(status)) {
                    String msg = taskRoot.path("output").path("message").asText();
                    String code = taskRoot.path("output").path("code").asText();
                    System.err.println("[ImageGenerator] 任务失败！");
                    System.err.println("  状态码: " + code);
                    System.err.println("  消息: " + msg);
                    System.err.println("  完整响应: " + pollResp.body());
                    throw new RuntimeException("图片生成失败: " + msg);
                }
                // PENDING / RUNNING → 继续等
            }

            throw new RuntimeException("图片生成超时，请稍后重试");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("被中断", e);
        } catch (Exception e) {
            System.err.println("[ImageGenerator] 生成失败！");
            System.err.println("  请求URL: " + imageGenUrl);
            System.err.println("  API Key前缀: " + (apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) + "..." : "null"));
            System.err.println("  异常类型: " + e.getClass().getName());
            System.err.println("  异常信息: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 URL 下载图片
     */
    private byte[] downloadImage(String imageUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
        return response.body();
    }
}
