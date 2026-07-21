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
    private static final int MAX_HISTORY_CONTENT_LENGTH = 2000;

    public static class AICallResult {
        public final boolean hasToolCall;   // true=要调工具, false=普通回复
        public final String text;           // 普通回复的文字
        public final String toolName;       // 工具名（如 query_weather）
        public final String toolArguments;  // 工具参数（JSON字符串，如 {"city":"北京"}）
        public final String toolCallId;     // 工具调用ID（第二轮请求要用）

        private AICallResult(boolean hasToolCall, String text,
                             String toolName, String toolArguments, String toolCallId) {
            this.hasToolCall = hasToolCall;
            this.text = text;
            this.toolName = toolName;
            this.toolArguments = toolArguments;
            this.toolCallId = toolCallId;
        }

        static AICallResult text(String text) {
            return new AICallResult(false, text, null, null, null);
        }

        static AICallResult toolCall(String name, String args, String id) {
            return new AICallResult(true, null, name, args, id);
        }
    }

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

    private String buildSystemPrompt() {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter
                        .ofPattern("yyyy年MM月dd日 HH:mm E", java.util.Locale.CHINA));
        return "你是一个微信智能助手，可以查天气、闲聊。\n"
                + "当前时间：" + now + "\n"
                + "回复要简洁口语化，控制在120字以内。\n"
                + "当用户询问天气时，请调用 query_weather 工具。\n"
                + "如果用户没有指定城市，不要传 city 参数，系统会自动定位用户所在城市。\n"
                + "你可以接收语音消息（已转为文字），像正常文字消息一样处理。\n"
                + "\n"
                + "===== 回复方式规则（必须遵守） =====\n"
                + "你的回复必须在最开头加上 [voice] 或 [text] 标记，然后换行写正文。\n"
                + "判断规则：\n"
                + "1. 用户消息以 [语音消息] 开头 → 默认用 [voice] 回复\n"
                + "1.5. 用户消息以 [用户要求语音回答] 开头 → 必须用 [voice] 回复\n"
                + "2. 但如果回复内容含3个以上数字（如天气数据）→ 用 [text]\n"
                + "3. 用户明确说'用语音''读给我听''念出来' → 用 [voice]\n"
                + "4. 用户明确说'打字''文字回复' → 用 [text]\n"
                + "5. 普通文字消息且没要求语音 → 用 [text]\n"
                + "6. 不确定时 → 用 [text]\n"
                + "示例：\n"
                + "[voice]\n你好呀！\n"
                + "[text]\n北京今天晴，气温35度。\n"
                + "===== 音色规则 =====\n"
                + "用户说'切换音色'或'换成XX'时，系统会自动处理，你不需要回答'已切换'，只需正常回复即可。\n"
                + "===== 规则结束 =====\n"
                + "其他问题直接回复即可。\n"
                + "===== 语音音色 =====\n"
                + "用户可以选择音色来语音回复，可用音色: 男生(默认)、女生、童声、粤语、东北话、陕西话等。\n"
                + "如果用户说'用女生读'或'换童声'之类的，你只需正常回复，系统会自动匹配音色。\n";
    }

    private List<Map<String, Object>> buildTools() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "query_weather",
                                "description", "查询指定城市的实时天气情况，包括温度、湿度、风向等，如果用户没有指定城市，不要传city参数，系统会自动定位",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "city", Map.of(
                                                        "type", "string",
                                                        "description", "城市名称，如北京、上海、广州。用户未指定时不要传此参数"
                                                )
                                        ),
                                        "required", List.of()
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "generate_image",
                                "description", "根据用户的描述生成一张图片。当用户要求画图、生成图片、画一张XX时调用此工具",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "description", Map.of(
                                                        "type", "string",
                                                        "description", "对要生成的图片的详细描述，如'一只可爱的橘猫'"
                                                )
                                        ),
                                        "required", List.of("description")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "generate_document",
                                "description", "根据用户的要求生成文档文件。当用户要求生成报告、总结文档、制作表格、导出文件时调用此工具",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "content", Map.of(
                                                        "type", "string",
                                                        "description", "文档的完整内容"
                                                ),
                                                "format", Map.of(
                                                        "type", "string",
                                                        "description", "文档格式: txt/md/csv/pdf/docx/xlsx",
                                                        "enum", List.of("txt", "md", "csv", "pdf", "docx", "xlsx")
                                                ),
                                                "file_name", Map.of(
                                                        "type", "string",
                                                        "description", "文件名（不含扩展名），如'天气报告'"
                                                )
                                        ),
                                        "required", List.of("content", "format")
                                )
                        )
                )
        );
    }

    public AICallResult callWithTools(String userId, String userMessage) {
        try {
            // 1. 取出该用户的历史对话
            List<Map<String, String>> history = historyMap.getOrDefault(userId, new ArrayList<>());

            // 2. 拼 messages（用 Map<String,Object> 因为后面要混合不同格式）
            List<Map<String, Object>> allMessages = new ArrayList<>();
            allMessages.add(Map.of("role", "system", "content", buildSystemPrompt()));
            for (Map<String, String> h : history) {
                allMessages.add(new java.util.LinkedHashMap<>(h));
            }
            allMessages.add(Map.of("role", "user", "content", userMessage));

            // 3. 拼 body（带 tools）
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", "qwen3.7-max");
            body.put("messages", allMessages);
            body.put("temperature", 0.3);
            body.put("tools", buildTools());

            // 4. 发请求
            String json = objectMapper.writeValueAsString(body);
            System.out.println(">>> [callWithTools] 请求体: "
                    + json.substring(0, Math.min(json.length(), 300)) + "...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            System.out.println(">>> [callWithTools] 响应: "
                    + response.body().substring(0, Math.min(response.body().length(), 300)) + "...");

            // 5. 解析返回
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("choices").get(0).path("message");

            // 6. 检查有没有 tool_calls
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray() && toolCalls.size() > 0) {
                JsonNode firstCall = toolCalls.get(0);
                String toolName = firstCall.path("function").path("name").asText();
                String toolArgs = firstCall.path("function").path("arguments").asText();
                String toolCallId = firstCall.path("id").asText();
                System.out.println(">>> [callWithTools] AI 要求调用工具: " + toolName
                        + " 参数: " + toolArgs);
                return AICallResult.toolCall(toolName, toolArgs, toolCallId);
            }

            // 7. 没有工具调用，就是普通文字回复
            String reply = message.path("content").asText();
            System.out.println(">>> [callWithTools] AI 普通回复: " + reply);

            // 存历史
            history.add(Map.of("role", "user", "content", truncateContent(userMessage)));
            history.add(Map.of("role", "assistant", "content", reply));
            trimHistory(history);
            historyMap.put(userId, history);

            return AICallResult.text(reply);

        } catch (Exception e) {
            System.err.println("!!! [callWithTools] 失败: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("AI 调用失败", e);
        }
    }

    public String callWithToolResult(String userId, String userMessage,
                                    String toolName, String toolArguments,
                                    String toolCallId, String toolResult) {
        try {
            List<Map<String, String>> history = historyMap.getOrDefault(userId, new ArrayList<>());

            // 拼 messages：system + history + user + assistant(tool_calls) + tool(result)
            List<Map<String, Object>> allMessages = new ArrayList<>();
            allMessages.add(Map.of("role", "system", "content", buildSystemPrompt()));
            for (Map<String, String> h : history) {
                allMessages.add(new java.util.LinkedHashMap<>(h));
            }
            // 用户原始问题
            allMessages.add(Map.of("role", "user", "content", userMessage));

            // assistant 的工具调用消息（content 必须为空字符串，不能为 null）
            Map<String, Object> assistantMsg = new java.util.LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", "");
            assistantMsg.put("tool_calls", List.of(
                    Map.of(
                            "id", toolCallId,
                            "type", "function",
                            "function", Map.of(
                                    "name", toolName,
                                    "arguments", toolArguments
                            )
                    )
            ));
            allMessages.add(assistantMsg);

            // 工具结果
            allMessages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", toolCallId,
                    "content", toolResult
            ));

            // 拼 body（这次不带 tools，让 AI 直接回复）
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", "qwen3.7-max");
            body.put("messages", allMessages);
            body.put("temperature", 0.3);

            String json = objectMapper.writeValueAsString(body);
            System.out.println(">>> [callWithToolResult] 请求体: "
                    + json.substring(0, Math.min(json.length(), 300)) + "...");

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

            System.out.println(">>> [callWithToolResult] AI 最终回复: " + reply);

            // 存历史（只存用户问题和最终回复，不存中间的工具调用）
            history.add(Map.of("role", "user", "content", truncateContent(userMessage)));
            history.add(Map.of("role", "assistant", "content", reply));
            trimHistory(history);
            historyMap.put(userId, history);

            return reply;

        } catch (Exception e) {
            System.err.println("!!! [callWithToolResult] 失败: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("AI 调用失败", e);
        }
    }

    /**
     * 截断历史，只保留最近 MAX_HISTORY_ROUNDS 轮
     */
    private void trimHistory(List<Map<String, String>> history) {
        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        if (history.size() > maxMessages) {
            history.subList(0, history.size() - maxMessages).clear();
        }
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

    private static String truncateContent(String content) {
        if (content == null) return "";
        if (content.length() <= MAX_HISTORY_CONTENT_LENGTH) return content;
        return content.substring(0, MAX_HISTORY_CONTENT_LENGTH)
                + "\n...[内容过长已截断，剩余 " + (content.length() - MAX_HISTORY_CONTENT_LENGTH) + " 字]";
    }

}