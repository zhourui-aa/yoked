package org.example.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.deepseek.model.ChatMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * DeepSeek Chat Completions 客户端，对标官方 Node.js SDK 的简洁用法。
 *
 * <pre>{@code
 * DeepSeekClient ds = new DeepSeekClient(System.getenv("DEEPSEEK_API_KEY"));
 * String reply = ds.chat(List.of(
 *     ChatMessage.system("You are a helpful assistant."),
 *     ChatMessage.user("Hello")
 * ));
 * }</pre>
 */
public class DeepSeekClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE_URL = "https://api.deepseek.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final String apiKey;
    private String model = "deepseek-v4-pro";
    private String thinkingType;        // "enabled" / null
    private String reasoningEffort;     // "high" / "medium" / "low" / null

    public DeepSeekClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /** 设置模型，默认 {@code deepseek-v4-pro} */
    public DeepSeekClient model(String model) { this.model = model; return this; }

    /** 开启思考模式，等同于官方示例的 {@code thinking: {"type": "enabled"}} */
    public DeepSeekClient thinking() { this.thinkingType = "enabled"; return this; }

    /** 设置推理努力程度：{@code high | medium | low} */
    public DeepSeekClient reasoningEffort(String level) { this.reasoningEffort = level; return this; }

    /** 单轮对话：发送消息列表，返回模型回复文本 */
    public String chat(List<ChatMessage> messages) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", false);

        // thinking
        if (thinkingType != null) {
            ObjectNode thinking = JSON.createObjectNode();
            thinking.put("type", thinkingType);
            body.set("thinking", thinking);
        }
        // reasoning_effort
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }

        // messages
        ArrayNode arr = JSON.createArrayNode();
        for (ChatMessage m : messages) {
            ObjectNode node = JSON.createObjectNode();
            node.put("role", m.role());
            node.put("content", m.content());
            arr.add(node);
        }
        body.set("messages", arr);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp;
        try { resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }

        if (resp.statusCode() != 200) {
            throw new IOException("DeepSeek API error " + resp.statusCode() + ": " + resp.body());
        }

        var root = JSON.readTree(resp.body());
        var choices = root.path("choices");
        if (choices.isEmpty()) throw new IOException("DeepSeek 返回空 choices");
        return choices.get(0).path("message").path("content").asText();
    }
}
