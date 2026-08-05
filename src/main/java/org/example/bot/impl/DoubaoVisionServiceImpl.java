package org.example.bot.impl;

import com.volcengine.ark.runtime.model.completion.chat.*;
import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.example.bot.service.VisionService;
import org.example.bot.util.ConfigUtil;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 豆包视觉理解服务 — 基于火山引擎 Ark SDK。
 *
 * <p>使用 Doubao Vision 模型分析图片内容。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * VisionService vision = new DoubaoVisionServiceImpl();
 * String result = vision.analyze(imageBytes, "描述这张图片");
 * }</pre>
 */
public class DoubaoVisionServiceImpl implements VisionService {

    private static final String BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    private static final String MODEL = "doubao-seed-2-0-lite-260215";
    private static final String DEFAULT_PROMPT = "请描述这张图片的内容，用中文回复。";

    private final ArkService service;

    /**
     * @throws IllegalStateException 如果未配置 ark.api.key
     */
    public DoubaoVisionServiceImpl() {
        String apiKey = ConfigUtil.get("ark.vision.api.key", "ARK_VISION_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "未找到视觉理解 API Key。请在 config.properties 中设置 ark.vision.api.key。\n"
                + "获取方式：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey");
        }

        ConnectionPool pool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        this.service = ArkService.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey.trim())
                .dispatcher(dispatcher)
                .connectionPool(pool)
                .build();

        System.out.println("[Vision] ✅ 视觉理解服务已就绪（模型: " + MODEL + "）");
    }

    /**
     * 分析图片内容。
     *
     * <p>将图片编码为 base64，通过 Doubao Vision 模型进行理解。
     *
     * @param imageBytes 图片字节数组
     * @param prompt     对图片的提问，为 {@code null} 或空时使用默认提问
     * @return AI 对图片的描述
     */
    @Override
    public String analyze(byte[] imageBytes, String prompt) {
        String question = (prompt != null && !prompt.isBlank()) ? prompt : DEFAULT_PROMPT;

        // 将图片编码为 base64 data URI
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String imageUrl = "data:image/jpeg;base64," + base64;

        // 构建图片内容
        ChatCompletionContentPart.ChatCompletionContentPartImageURL imagePart =
            new ChatCompletionContentPart.ChatCompletionContentPartImageURL(imageUrl);

        ChatCompletionContentPart imageContent = ChatCompletionContentPart.builder()
                .type("image_url")
                .imageUrl(imagePart)
                .build();

        ChatCompletionContentPart textContent = ChatCompletionContentPart.builder()
                .type("text")
                .text(question)
                .build();

        // 构建消息
        ChatMessage message = new ChatMessage();
        message.setRole(ChatMessageRole.USER);
        message.setContent(List.of(imageContent, textContent));

        // 构建请求
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel(MODEL);
        request.setMessages(List.of(message));

        // 调用 API
        try {
            ChatCompletionResult result = service.createChatCompletion(request);
            String reply = result.getChoices().get(0).getMessage().stringContent();
            System.out.println("[Vision] 图片分析完成");
            return reply;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("SensitiveContentDetected")) {
                System.err.println("[Vision] ⚠ 图片被安全策略拦截");
                return "抱歉，这张图片可能包含敏感内容，我无法进行分析。";
            }
            System.err.println("[Vision] ❌ 分析失败: " + msg);
            return "抱歉，我无法识别这张图片的内容，请稍后再试。";
        }
    }

    public void close() {
        service.shutdownExecutor();
    }
}
