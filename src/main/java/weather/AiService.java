package weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class AiService {
    private static final String API_KEY = "sk-ws-H.EDERRRR.G8ME.MEYCIQCQkc1nKAkznZiviFwkMNCWhkhZJta-JgWfpfhJ0jWtNAIhAIY2O8XlHDvK4YHEcq8t6AbbnxaWQjYhSdecSLY-UOA6";

    // 聊天/视觉 API（OpenAI 兼容）
    private static final String CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    // 图片生成 API（万相原生端点）
    private static final String IMAGE_GEN_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final OkHttpClient imageGenClient; // 图片生成用更长超时
    private final String modelName;

    public AiService() {
        this("qwen-plus");
    }

    public AiService(String modelName) {
        this.modelName = modelName;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        // 图片生成可能需要 60-120 秒
        this.imageGenClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        System.out.println("🤖 AiService 初始化，模型: " + modelName);
    }

    public String getModelName() {
        return modelName;
    }

    // ========== 辅助：获取实际聊天模型（画图模型不支持纯文本）==========
    private String getChatModel() {
        return modelName.contains("wan2.7-image") ? "qwen-plus" : modelName;
    }

    String getVisionModel() {
        if (modelName.contains("vl")) return modelName;
        return "qwen-vl-plus";
    }

    // ==================== 聊天对话 ====================
    public String chat(String userMessage) throws AiException {
        return chatWithSystem(null, userMessage);
    }

    public String chatWithSystem(String systemPrompt, String userMessage) throws AiException {
        try {
            String actualModel = getChatModel();
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", actualModel);

            JsonArray messages = new JsonArray();

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", systemPrompt);
                messages.add(systemMsg);
            }

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userMessage);
            messages.add(userMsg);

            requestBody.add("messages", messages);
            requestBody.addProperty("temperature", 0.7);

            System.out.println("🤖 调用模型: " + actualModel);
            System.out.println("🤖 用户输入: " + userMessage.substring(0, Math.min(50, userMessage.length())) + "...");

            return executeChatRequest(requestBody);

        } catch (Exception e) {
            throw new AiException("调用错误: " + e.getMessage());
        }
    }

    // ==================== 图片分析（多模态）====================
    public String analyzeImage(String imageUrl, String question) throws AiException {
        if (imageUrl == null || imageUrl.isEmpty()) {
            throw new AiException("图片 URL 为空");
        }

        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", getVisionModel());

            JsonArray messages = new JsonArray();

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");

            JsonArray content = new JsonArray();

            JsonObject imageContent = new JsonObject();
            imageContent.addProperty("type", "image_url");
            JsonObject imageUrlObj = new JsonObject();
            imageUrlObj.addProperty("url", imageUrl);
            imageContent.add("image_url", imageUrlObj);
            content.add(imageContent);

            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", question);
            content.add(textContent);

            userMsg.add("content", content);
            messages.add(userMsg);

            requestBody.add("messages", messages);

            System.out.println("🖼️ 分析图片: " + imageUrl.substring(0, Math.min(50, imageUrl.length())) + "...");
            System.out.println("🖼️ 使用视觉模型: " + getVisionModel());

            return executeChatRequest(requestBody);

        } catch (Exception e) {
            throw new AiException("图片分析错误: " + e.getMessage());
        }
    }

    // ==================== 图片生成（万相 wan2.7-image）====================
    /**
     * 文生图
     * @param prompt 图片描述
     * @param size 尺寸: 1024x1024 / 1280x720 / 720x1280 / 2048x2048(2K) / 4096x4096(4K,仅pro)
     * @param genModel 生成模型: wan2.7-image / wan2.7-image-pro
     * @return 生成的图片URL
     */
    public String generateImage(String prompt, String size, String genModel) throws AiException {
        if (prompt == null || prompt.isEmpty()) {
            throw new AiException("图片描述不能为空");
        }

        try {
            // DashScope 原生格式请求
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", genModel);

            // input.messages
            JsonObject input = new JsonObject();
            JsonArray messages = new JsonArray();

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");

            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("text", prompt);
            content.add(textContent);

            userMsg.add("content", content);
            messages.add(userMsg);
            input.add("messages", messages);
            requestBody.add("input", input);

            // parameters
            JsonObject parameters = new JsonObject();
            parameters.addProperty("size", size);
            parameters.addProperty("n", 1);
            // wan2.7-image-pro 支持 thinking_mode 提升质量
            if (genModel.contains("pro")) {
                parameters.addProperty("thinking_mode", true);
            }
            requestBody.add("parameters", parameters);

            System.out.println("🎨 生成图片模型: " + genModel);
            System.out.println("🎨 提示词: " + prompt.substring(0, Math.min(40, prompt.length())) + "...");
            System.out.println("🎨 尺寸: " + size);

            return executeImageGenRequest(requestBody);

        } catch (Exception e) {
            throw new AiException("图片生成错误: " + e.getMessage());
        }
    }

    // ==================== HTTP 执行方法 ====================

    /**
     * 执行聊天/视觉请求 (OpenAI 兼容端点)
     */
    private String executeChatRequest(JsonObject requestBody) throws AiException {
        try {
            Request request = new Request.Builder()
                    .url(CHAT_URL)
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, requestBody.toString()))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body().string();

                if (!response.isSuccessful()) {
                    throw new AiException("HTTP错误: " + response.code() + ", body=" + responseBody);
                }

                JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                JsonArray choices = json.getAsJsonArray("choices");

                if (choices == null || choices.size() == 0) {
                    throw new AiException("模型返回空结果");
                }

                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message == null || !message.has("content")) {
                    throw new AiException("模型返回格式异常");
                }

                String content = message.get("content").getAsString();
                System.out.println("✅ 模型回复: " + content.substring(0, Math.min(100, content.length())) + "...");
                return content;
            }

        } catch (IOException e) {
            throw new AiException("网络错误: " + e.getMessage());
        }
    }

    /**
     * 执行图片生成请求 (DashScope 原生端点)
     */
    private String executeImageGenRequest(JsonObject requestBody) throws AiException {
        try {
            Request request = new Request.Builder()
                    .url(IMAGE_GEN_URL)
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, requestBody.toString()))
                    .build();

            try (Response response = imageGenClient.newCall(request).execute()) {
                String responseBody = response.body().string();

                if (!response.isSuccessful()) {
                    throw new AiException("HTTP错误: " + response.code() + ", body=" + responseBody);
                }

                JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

                // 解析万相响应格式: output.choices[0].message.content[0].image
                JsonObject output = json.getAsJsonObject("output");
                if (output == null) {
                    throw new AiException("响应缺少 output 字段: " + responseBody);
                }

                JsonArray choices = output.getAsJsonArray("choices");
                if (choices == null || choices.size() == 0) {
                    throw new AiException("图片生成返回空结果");
                }

                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                JsonArray content = message.getAsJsonArray("content");

                if (content == null || content.size() == 0) {
                    throw new AiException("生成结果为空");
                }

                for (int i = 0; i < content.size(); i++) {
                    JsonObject item = content.get(i).getAsJsonObject();
                    if ("image".equals(item.get("type").getAsString())) {
                        String imageUrl = item.get("image").getAsString();
                        System.out.println("✅ 图片生成成功: " + imageUrl.substring(0, Math.min(80, imageUrl.length())) + "...");
                        return imageUrl;
                    }
                }

                throw new AiException("响应中未找到图片URL");
            }

        } catch (IOException e) {
            throw new AiException("网络错误: " + e.getMessage());
        }
    }

    public String analyzeWeather(String city, String weatherData) throws AiException {
        String prompt = "你是一个天气助手。请根据以下天气数据，用友好、生动的方式向用户介绍" + city + "的天气情况，" +
                "并给出适当的穿衣、出行建议。数据如下：\n\n" + weatherData;

        return chatWithSystem(
                "你是一个专业的天气助手，擅长用通俗易懂的语言解释天气数据，并给出实用的生活建议。",
                prompt
        );
    }

    public String chatWithImage(String text, String base64Image) throws AiException {
        return analyzeImage(base64Image, text);
    }

    public static class AiException extends Exception {
        public AiException(String message) {
            super(message);
        }
    }
}