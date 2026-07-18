package com.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AIClient {

    private final Map<String,List<Map<String,String>>> historyMap = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_ROUNDS = 10;
    private final String apiKey;
    private final String apiUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    // ... 构造方法 + 核心方法
    public AIClient() {
        // 读 application.properties
        java.util.Properties props = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            props.load(in);
        } catch (Exception e) {
            throw new RuntimeException("无法读取配置文件", e);
        }
        this.apiKey = props.getProperty("ai.api.key");
        this.apiUrl = props.getProperty("ai.api.url");

        if (apiKey == null || apiKey.isBlank() || apiKey.contains("你的")) {
            throw new RuntimeException("AI API Key 未配置，请修改 application.properties");
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String analyzeIntent(String userMessage) {
        // 系统提示词：告诉 AI 它的角色和输出格式
        String systemPrompt = ""
                + "你是天气助手。分析用户消息，返回JSON格式：\n"
                + "{ \"isWeather\": true/false, \"city\": \"城市名\" }\n"
                + "规则：\n"
                + "1. 用户如果是在查天气，isWeather=true，city填城市名\n"
                + "2. 如果不是查天气，isWeather=false，city填空字符串\n"
                + "3. 只返回JSON，不要解释";

        return callAIBare(systemPrompt, userMessage);
    }

    /**
     * 把天气数据交给 AI，让它生成友好的自然语言回复
     */
    public String formatWeatherReply(String userId,String userQuestion, String weatherData) {
        String systemPrompt = ""
                + "你是天气助手。用户问了天气问题，下面是查到的天气数据。\n"
                + "请用友好、口语化的语气回复，像朋友聊天一样。\n"
                + "可以适当加一点穿衣建议或出行提示。";

        String userPrompt = "用户问：" + userQuestion + "\n天气数据：" + weatherData;

        return callAIWithHistory(userId,systemPrompt, userPrompt);
    }

    /**
     * 发请求给 AI，返回 AI 说的第一句话
     */
    private String callAIBare(String systemPrompt, String userMessage) {

        try {
            Map<String, Object> body = Map.of(
                    "model", "qwen3.7-max",
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)

                    ),
                    "temperature", 0.3
            );

            String json = objectMapper.writeValueAsString(body);
            System.out.println(">>> AI 请求 URL: " + apiUrl);
            System.out.println(">>> AI 请求体: " + json.substring(0, Math.min(json.length(), 200)) + "...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            System.out.println(">>> AI 响应状态: " + response.statusCode());
            System.out.println(">>> AI 响应体: " + response.body().substring(0, Math.min(response.body().length(), 200)) + "...");

            // ... 解析代码不变 ...
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").get(0)
                    .path("message").path("content").asText();

        } catch (Exception e) {
            System.err.println("!!! AI 调用失败: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("AI API 调用失败", e);
        }
    }

    private String callAIWithHistory(String userId, String systemPrompt, String userMessage) {
        try {
            // 1. 取出该用户的历史对话
            List<Map<String, String>> history = historyMap.getOrDefault(userId, new ArrayList<>());

            // 2. 拼完整 messages 数组：system + 历史 + 当前问题
            List<Map<String, String>> allMessages = new ArrayList<>();
            allMessages.add(Map.of("role", "system", "content", systemPrompt));
            allMessages.addAll(history);
            allMessages.add(Map.of("role", "user", "content", userMessage));

            // 3. 发请求
            Map<String, Object> body = Map.of(
                    "model", "qwen3.7-max",
                    "messages", allMessages,
                    "temperature", 0.3
            );

            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(response.body());
            String reply = root.path("choices").get(0)
                    .path("message").path("content").asText();

            // 4. 把这一轮对话存进历史
            history.add(Map.of("role", "user", "content", userMessage));
            history.add(Map.of("role", "assistant", "content", reply));

            // 5. 如果历史太长，只保留最近 MAX_HISTORY_ROUNDS 轮（每轮2条）
            int maxMessages = MAX_HISTORY_ROUNDS * 2;
            if (history.size() > maxMessages) {
                history = new ArrayList<>(
                        history.subList(history.size() - maxMessages, history.size())
                );
            }

            historyMap.put(userId, history);
            return reply;

        } catch (Exception e) {
            System.err.println("!!! AI 调用失败: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("AI API 调用失败", e);
        }
    }

    public String chat(String userId,String userMessage) {
        String systemPrompt = "你是一个友好的助手，用简洁的口语回复用户。回复控制在50字以内。";
        return callAIWithHistory(userId,systemPrompt, userMessage);
    }

    public void addToHistory(String userId, String userMessage, String assistantReply) {
        List<Map<String, String>> history = historyMap.getOrDefault(userId, new ArrayList<>());
        history.add(Map.of("role", "user", "content", userMessage));
        history.add(Map.of("role", "assistant", "content", assistantReply));

        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        if (history.size() > maxMessages) {
            history = new ArrayList<>(history.subList(history.size() - maxMessages, history.size()));
        }
        historyMap.put(userId, history);
    }
}