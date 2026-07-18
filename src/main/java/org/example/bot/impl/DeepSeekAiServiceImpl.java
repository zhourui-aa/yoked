package org.example.bot.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.example.bot.model.DrawIntent;
import org.example.bot.model.WeatherIntent;
import org.example.bot.service.AiService;
import org.example.bot.util.ConfigUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek AI 对话服务 — 基于 OpenAI Java SDK。
 *
 * <p>DeepSeek API 完全兼容 OpenAI 接口格式，只需把 baseUrl 指向 DeepSeek 即可。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * AiService ai = new DeepSeekAiServiceImpl("你是一个友好的助手");
 * String reply = ai.chat("你好");
 * }</pre>
 */
public class DeepSeekAiServiceImpl implements AiService {

    private static final String BASE_URL = "https://api.deepseek.com/v1";
    private static final String MODEL = "deepseek-v4-pro";
    private static final int MAX_HISTORY = 20;

    // 意图提取专用 system prompt — 强制 JSON 输出
    private static final String INTENT_SYSTEM_PROMPT =
        "你是一个意图识别助手。你的唯一任务是输出JSON，" +
        "不要输出任何解释、markdown代码块、或其他文字。只输出纯JSON。";

    private final String systemPrompt;
    private final OpenAIClient client;
    private final Gson gson = new Gson();
    private final List<String> roles = new ArrayList<>();
    private final List<String> contents = new ArrayList<>();

    /**
     * @param systemPrompt 系统提示词，如 "你是一个友好的微信助手，请用简洁中文回复。"
     * @throws IllegalStateException 如果未配置 deepseek.api.key
     */
    public DeepSeekAiServiceImpl(String systemPrompt) {
        this.systemPrompt = systemPrompt;

        String apiKey = ConfigUtil.get("deepseek.api.key", "DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "未找到 DeepSeek API Key。请在 config.properties 中设置 deepseek.api.key，\n"
                + "或设置环境变量 DEEPSEEK_API_KEY。获取方式：https://platform.deepseek.com/api_keys");
        }

        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .baseUrl(BASE_URL)
                .build();

        System.out.println("[AI] DeepSeek 服务已就绪（模型: " + MODEL + "）");
    }

    @Override
    public String chat(String userMessage) {
        try {
            roles.add("user");
            contents.add(userMessage);

            while (roles.size() > MAX_HISTORY) {
                roles.remove(0);
                contents.remove(0);
            }

            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .addSystemMessage(systemPrompt);

            for (int i = 0; i < roles.size(); i++) {
                if ("user".equals(roles.get(i))) {
                    builder.addUserMessage(contents.get(i));
                } else {
                    builder.addAssistantMessage(contents.get(i));
                }
            }

            ChatCompletion completion = client.chat().completions().create(
                    builder.model(MODEL).build());

            String reply = completion.choices().get(0).message().content().orElse("");

            roles.add("assistant");
            contents.add(reply);

            return reply;

        } catch (Exception e) {
            System.err.println("[AI] ❌ DeepSeek 调用失败: " + e.getMessage());
            return "抱歉，我暂时无法回复，请稍后再试。";
        }
    }

    // ---- 意图提取（独立 API 调用，不消耗对话历史）----

    @Override
    public DrawIntent extractDrawIntent(String userMessage) {
        String prompt = "分析这条消息是否想让AI生成/画/创建一张图片。" +
            "如果是，提取用户想要生成的主题描述，尽量保留原话中的细节和风格。" +
            "如果不是生图请求，isDraw设为false。" +
            "严格按此JSON格式输出：{\"isDraw\":true|false,\"prompt\":\"主题描述\"}\n\n" +
            "消息：" + userMessage;

        try {
            String json = quickAsk(prompt);
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            boolean isDraw = obj.has("isDraw") && obj.get("isDraw").getAsBoolean();
            String promptText = obj.has("prompt") && !obj.get("prompt").isJsonNull()
                ? obj.get("prompt").getAsString() : "";
            return new DrawIntent(isDraw, promptText.isBlank() ? "" : promptText);
        } catch (Exception e) {
            System.err.println("[AI] ⚠ 生图意图提取失败: " + e.getMessage());
            return DrawIntent.notDraw();  // 降级：当普通对话处理
        }
    }

    @Override
    public WeatherIntent extractWeatherIntent(String userMessage) {
        String prompt = "分析这条消息是否在询问天气相关信息（包括气温、下雨、冷热、刮风、是否需要带伞等）。" +
            "如果是，尝试从中提取城市名称（中文城市名）；如果没有明确城市名则city留空。" +
            "如果不是天气查询，isWeather设为false。" +
            "严格按此JSON格式输出：{\"isWeather\":true|false,\"city\":\"城市名\"}\n\n" +
            "消息：" + userMessage;

        try {
            String json = quickAsk(prompt);
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            boolean isWeather = obj.has("isWeather") && obj.get("isWeather").getAsBoolean();
            String city = obj.has("city") && !obj.get("city").isJsonNull()
                ? obj.get("city").getAsString() : "";
            return new WeatherIntent(isWeather, city.isBlank() ? "" : city);
        } catch (Exception e) {
            System.err.println("[AI] ⚠ 天气意图提取失败: " + e.getMessage());
            return WeatherIntent.notWeather();  // 降级：当普通对话处理
        }
    }

    /**
     * 单次快速 AI 调用 — 不读写对话历史，专用于意图识别等轻量任务。
     */
    private String quickAsk(String prompt) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addSystemMessage(INTENT_SYSTEM_PROMPT)
                .addUserMessage(prompt)
                .model(MODEL)
                .build();

        ChatCompletion completion = client.chat().completions().create(params);
        String content = completion.choices().get(0).message().content().orElse("{}");

        // 某些模型可能用 ```json ... ``` 包裹，清理掉
        content = content.strip();
        if (content.startsWith("```")) {
            content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").strip();
        }

        return content;
    }

    // ---- 对话历史管理 ----

    public void clearHistory() {
        roles.clear();
        contents.clear();
    }
}
