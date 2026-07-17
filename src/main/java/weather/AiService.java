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
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
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
        System.out.println("🤖 AiService 初始化，模型: " + modelName);
    }

    public String getModelName() {
        return modelName;
    }

    public String chat(String userMessage) throws AiException {
        return chatWithSystem(null, userMessage);
    }

    public String chatWithSystem(String systemPrompt, String userMessage) throws AiException {
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);

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

            System.out.println("🤖 调用模型: " + modelName);
            System.out.println("🤖 用户输入: " + userMessage.substring(0, Math.min(50, userMessage.length())) + "...");

            return executeRequest(requestBody);

        } catch (Exception e) {
            throw new AiException("调用错误: " + e.getMessage());
        }
    }

    /**
     * 分析图片内容（多模态）
     */
    public String analyzeImage(String imageUrl, String question) throws AiException {
        if (imageUrl == null || imageUrl.isEmpty()) {
            throw new AiException("图片 URL 为空");
        }

        try {
            JsonObject requestBody = new JsonObject();
            // 图片分析必须用视觉模型
            String visionModel = modelName.contains("vl") ? modelName : "qwen-vl-plus";
            requestBody.addProperty("model", visionModel);

            JsonArray messages = new JsonArray();

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");

            // 多模态内容格式：数组包含图片和文字
            JsonArray content = new JsonArray();

            // 图片部分
            JsonObject imageContent = new JsonObject();
            imageContent.addProperty("type", "image_url");
            JsonObject imageUrlObj = new JsonObject();
            imageUrlObj.addProperty("url", imageUrl);
            imageContent.add("image_url", imageUrlObj);
            content.add(imageContent);

            // 文字部分
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", question);
            content.add(textContent);

            userMsg.add("content", content);
            messages.add(userMsg);

            requestBody.add("messages", messages);

            System.out.println("🖼️ 分析图片: " + imageUrl.substring(0, Math.min(50, imageUrl.length())) + "...");
            System.out.println("🖼️ 使用视觉模型: " + visionModel);

            return executeRequest(requestBody);

        } catch (Exception e) {
            throw new AiException("图片分析错误: " + e.getMessage());
        }
    }

    /**
     * 执行HTTP请求并解析响应
     */
    private String executeRequest(JsonObject requestBody) throws AiException {
        try {
            Request request = new Request.Builder()
                    .url(BASE_URL)
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

    public String analyzeWeather(String city, String weatherData) throws AiException {
        String prompt = "你是一个天气助手。请根据以下天气数据，用友好、生动的方式向用户介绍" + city + "的天气情况，" +
                "并给出适当的穿衣、出行建议。数据如下：\n\n" + weatherData;

        return chatWithSystem(
                "你是一个专业的天气助手，擅长用通俗易懂的语言解释天气数据，并给出实用的生活建议。",
                prompt
        );
    }

    public static class AiException extends Exception {
        public AiException(String message) {
            super(message);
        }
    }
}