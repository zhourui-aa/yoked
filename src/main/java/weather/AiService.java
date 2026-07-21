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
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AiService {
    private static final String API_KEY = "sk-ws-H.EDERRRR.G8ME.MEYCIQCQkc1nKAkznZiviFwkMNCWhkhZJta-JgWfpfhJ0jWtNAIhAIY2O8XlHDvK4YHEcq8t6AbbnxaWQjYhSdecSLY-UOA6";

    // 聊天/视觉 API（OpenAI 兼容）
    private static final String CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    // 图片生成 API（万相原生端点）
    private static final String IMAGE_GEN_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final OkHttpClient imageGenClient;
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
        this.imageGenClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        System.out.println("🤖 AiService 初始化，模型: " + modelName);
    }

    public String getModelName() {
        return modelName;
    }

    private String getChatModel() {
        return modelName.contains("wan2.7-image") ? "qwen-plus" : modelName;
    }

    String getVisionModel() {
        if (modelName.contains("vl")) return modelName;
        return "qwen-vl-plus";
    }

    // ==================== 普通聊天 ====================
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

    // ==================== 带工具调用的对话（核心新增）====================
    /**
     * 支持 Function Calling 的对话。
     * AI 会自动判断是否需要调用工具，执行后再生成自然语言回复。
     */
    public String chatWithTools(String userMessage, List<Tool> tools) throws AiException {
        if (tools == null || tools.isEmpty()) {
            return chat(userMessage);
        }

        // 构建 tools 数组（OpenAI 兼容格式）
        JsonArray toolsArray = new JsonArray();
        for (Tool tool : tools) {
            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("type", "function");

            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.parametersSchema());

            toolObj.add("function", function);
            toolsArray.add(toolObj);
        }

        // 第一次请求：让模型判断是否需要调用工具
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", getChatModel());

        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "你是一个智能天气助手。当用户询问天气、穿衣建议、出行建议、是否需要带伞时，请使用 weather_query 工具获取实时天气数据，不要编造。");
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        requestBody.add("messages", messages);
        requestBody.add("tools", toolsArray);
        requestBody.addProperty("tool_choice", "auto");

        String responseBody = executeChatRaw(requestBody);
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");

        if (choices == null || choices.size() == 0) {
            throw new AiException("模型返回空结果");
        }

        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");

        // 没有 tool_calls，直接返回普通回复
        if (!message.has("tool_calls") || message.get("tool_calls").isJsonNull()) {
            return message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString()
                    : "模型无回复";
        }

        // 有 tool_calls，执行工具后再调一次模型
        JsonArray toolCalls = message.getAsJsonArray("tool_calls");

        // 构建第二轮 messages
        JsonArray newMessages = new JsonArray();
        newMessages.add(systemMsg);
        newMessages.add(userMsg);

        // 添加 assistant 的 tool_calls 消息
        JsonObject assistantMsg = new JsonObject();
        assistantMsg.addProperty("role", "assistant");
        assistantMsg.add("tool_calls", toolCalls);
        newMessages.add(assistantMsg);

        // 执行每个工具调用，添加结果
        for (int i = 0; i < toolCalls.size(); i++) {
            JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
            String id = toolCall.get("id").getAsString();
            JsonObject function = toolCall.getAsJsonObject("function");
            String name = function.get("name").getAsString();
            String arguments = function.get("arguments").getAsString();

            Tool tool = findTool(tools, name);
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            ToolResult result = tool.execute(args);

            JsonObject toolMsg = new JsonObject();
            toolMsg.addProperty("role", "tool");
            toolMsg.addProperty("tool_call_id", id);
            toolMsg.addProperty("content", result.content);
            newMessages.add(toolMsg);
        }

        // 第二次请求：让模型根据工具结果生成最终回复
        JsonObject finalRequest = new JsonObject();
        finalRequest.addProperty("model", getChatModel());
        finalRequest.add("messages", newMessages);

        String finalResponse = executeChatRaw(finalRequest);
        JsonObject finalJson = JsonParser.parseString(finalResponse).getAsJsonObject();
        JsonArray finalChoices = finalJson.getAsJsonArray("choices");

        if (finalChoices == null || finalChoices.size() == 0) {
            throw new AiException("模型最终返回空结果");
        }

        JsonObject finalMessage = finalChoices.get(0).getAsJsonObject().getAsJsonObject("message");
        return finalMessage.has("content") && !finalMessage.get("content").isJsonNull()
                ? finalMessage.get("content").getAsString()
                : "模型无回复";
    }

    private Tool findTool(List<Tool> tools, String name) throws AiException {
        for (Tool tool : tools) {
            if (tool.name().equals(name)) return tool;
        }
        throw new AiException("未知工具: " + name);
    }

    // ==================== 图片分析 ====================
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

    // ==================== 图片生成 ====================
    public String generateImage(String prompt, String size, String genModel) throws AiException {
        if (prompt == null || prompt.isEmpty()) {
            throw new AiException("图片描述不能为空");
        }

        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", genModel);

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

            JsonObject parameters = new JsonObject();
            parameters.addProperty("size", size);
            parameters.addProperty("n", 1);
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
     * 执行聊天请求，返回原始响应 JSON 字符串（用于 Function Calling）
     */
    private String executeChatRaw(JsonObject requestBody) throws AiException {
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
                return responseBody;
            }
        } catch (IOException e) {
            throw new AiException("网络错误: " + e.getMessage());
        }
    }

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

    public String summarizeDocument(String content, String fileName) throws AiException {
        String system = "你是一位专业的文档分析助手，擅长快速提炼文档核心内容，输出简洁、结构化的总结。";
        String prompt = "请对以下文档内容进行总结：\n\n" +
                "【文档名称】" + fileName + "\n\n" +
                "【要求】\n" +
                "1. 用 3-5 个要点提炼核心内容\n" +
                "2. 如有数据、结论、行动项请突出显示\n" +
                "3. 控制在 300 字以内\n" +
                "4. 使用中文回复\n\n" +
                "【文档内容】\n" + content;

        return chatWithSystem(system, prompt);
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