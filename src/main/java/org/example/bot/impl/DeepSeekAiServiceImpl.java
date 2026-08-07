package org.example.bot.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.*;
import db.ChatRepository;
import org.example.bot.service.AiService;
import org.example.bot.util.ConfigUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek AI 对话服务 — 支持多会话隔离 + Function Calling + 原生联网搜索。
 */
public class DeepSeekAiServiceImpl implements AiService {

    private static final String BASE_URL = "https://api.deepseek.com/v1";
    private static final String RESPONSES_URL = "https://api.deepseek.com/responses";
    /** 主模型固定为 Flash — 保证原生联网搜索默认可用，不允许配置覆盖 */
    private static final String MODEL = "deepseek-v4-flash";
    private static final int MAX_HISTORY = SessionManager.MAX_HISTORY;

    private final OpenAIClient client;
    private final Gson gson = new Gson();
    private final SessionManager sessionManager;
    private final BotState botState;
    private final ChatRepository repo;
    private final String apiKey;
    /** 原生联网开关 — 可在 config.properties 用 deepseek.websearch 关闭 */
    private final boolean webSearchEnabled;
    /** 联网结果缓存（避免同一关键词重复触发 9 秒+ 的慢速联网） */
    private static final java.util.Map<String, CacheEntry> searchCache = new java.util.concurrent.ConcurrentHashMap<>();
    private record CacheEntry(String result, long time) {}

    public DeepSeekAiServiceImpl(String defaultPersona, String techInstructions) {

        String apiKey = ConfigUtil.get("deepseek.api.key", "DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "未找到 DeepSeek API Key。请在 config.properties 中设置 deepseek.api.key，\n"
                + "或设置环境变量 DEEPSEEK_API_KEY。");
        }
        this.apiKey = apiKey.trim();

        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .baseUrl(BASE_URL)
                .build();
        this.sessionManager = new SessionManager(defaultPersona, techInstructions);
        this.botState = new BotState();
        this.repo = new ChatRepository();

        String ws = ConfigUtil.get("deepseek.websearch", "DEEPSEEK_WEBSEARCH");
        this.webSearchEnabled = ws == null || ws.isBlank() || Boolean.parseBoolean(ws.strip());

        System.out.println("[AI] DeepSeek 服务已就绪（模型: " + MODEL
            + (webSearchEnabled ? "，原生联网已开启）" : "，原生联网已关闭）"));
    }

    /**
     * 原生联网搜索 — 用 DeepSeek Responses API 的 web_search 工具（Chat Completions 端点不支持联网）。
     * 返回整理后的搜索结果文本，供 FC 工具回传给模型。带 5 分钟结果缓存。
     */
    public String webSearch(String query) {
        if (query == null || query.isBlank()) return "请提供搜索关键词。";
        if (!webSearchEnabled) return "原生联网搜索已关闭（config.properties 中 deepseek.websearch=false）。";
        String key = query.strip();
        // 结果缓存：同一关键词 5 分钟内不重复联网（联网耗时 9 秒+，缓存显著降低延迟）
        synchronized (searchCache) {
            CacheEntry ce = searchCache.get(key);
            if (ce != null && System.currentTimeMillis() - ce.time < 5 * 60 * 1000L) {
                return ce.result;
            }
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", MODEL);
            body.addProperty("input", query);
            JsonArray tools = new JsonArray();
            JsonObject searchTool = new JsonObject();
            searchTool.addProperty("type", "web_search");
            tools.add(searchTool);
            body.add("tools", tools);

            String resp = httpPostJson(RESPONSES_URL, body.toString());
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            if (root.has("error") && !root.get("error").isJsonNull()) {
                return "联网搜索失败：" + root.get("error").getAsString();
            }
            // 从 output 中提取 message 文本（含搜索结果摘要）
            StringBuilder sb = new StringBuilder();
            if (root.has("output") && root.get("output").isJsonArray()) {
                for (var item : root.getAsJsonArray("output")) {
                    JsonObject o = item.getAsJsonObject();
                    String itemType = o.has("type") && !o.get("type").isJsonNull() ? o.get("type").getAsString() : "";
                    if ("message".equals(itemType)
                        && o.has("content") && o.get("content").isJsonArray()) {
                        for (var part : o.getAsJsonArray("content")) {
                            JsonObject p = part.getAsJsonObject();
                            String pType = p.has("type") && !p.get("type").isJsonNull() ? p.get("type").getAsString() : "";
                            if ("output_text".equals(pType) && p.has("text") && !p.get("text").isJsonNull()) {
                                sb.append(p.get("text").getAsString());
                            }
                        }
                    }
                }
            }
            String text = sb.toString().strip();
            String result = text.isEmpty()
                ? "联网搜索未返回有效结果，请尝试更换关键词。"
                : "🔍 联网搜索结果：\n" + text;
            // 写入缓存
            synchronized (searchCache) {
                if (searchCache.size() > 50) searchCache.clear(); // 防缓存无限增长
                searchCache.put(key, new CacheEntry(result, System.currentTimeMillis()));
            }
            return result;
        } catch (Exception e) {
            System.err.println("[AI] ❌ 联网搜索失败: " + e.getMessage());
            return "联网搜索失败：" + e.getMessage();
        }
    }

    private String httpPostJson(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    /** per-user 媒体缓存（图片、文档、新闻），线程安全 */
    public BotState getBotState() { return botState; }
    public ChatRepository getChatRepo() { return repo; }

    /** 如果 session 是空的，从库恢复历史 */
    private void restoreSession(String userId, Session session) {
        repo.ensureSession(userId, session.name, session.persona);
        if (!session.roles.isEmpty()) return;
        var history = repo.loadHistory(userId, session.name, MAX_HISTORY);
        if (!history.isEmpty()) {
            session.loadFromDb(history);
            System.out.println("[AI] 从库恢复 " + history.size() + " 条: " + userId + "/" + session.name);
        }
    }

    @Override
    public void setPersona(String userId, String persona) {
        sessionManager.setPersona(userId, persona);
        repo.updatePersona(userId, sessionManager.getOrCreate(userId).name, persona);
    }

    @Override
    public void clearSession(String userId) {
        sessionManager.clearCurrent(userId);
    }

    // ---- 多会话聊天 ----

    @Override
    public String chat(String userId, String userMessage) {
        Session session = sessionManager.getOrCreate(userId);
        restoreSession(userId, session);

        // 避免 chatWithTools 返回 null 后降级 chat() 导致重复入库
        if (session.roles.isEmpty()
            || !"user".equals(session.roles.get(session.roles.size() - 1))
            || !session.contents.get(session.contents.size() - 1).equals(userMessage)) {
            session.add("user", userMessage);
            session.trim(MAX_HISTORY);
            repo.saveMessage(userId, session.name, "user", userMessage);
        }

        try {
            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .addSystemMessage(sessionManager.fullSystemPrompt(session));

            for (int i = 0; i < session.roles.size(); i++) {
                if ("user".equals(session.roles.get(i))) {
                    builder.addUserMessage(session.contents.get(i));
                } else {
                    builder.addAssistantMessage(session.contents.get(i));
                }
            }

            ChatCompletion completion = client.chat().completions().create(
                    builder.model(MODEL).build());

            var choices = completion.choices();
            if (choices.isEmpty()) return "抱歉，模型未返回有效内容，请稍后再试。";
            String reply = choices.get(0).message().content().orElse("");
            session.add("assistant", reply);
            repo.saveMessage(userId, session.name, "assistant", reply);
            return reply;

        } catch (Exception e) {
            System.err.println("[AI] ❌ DeepSeek 调用失败: " + e.getMessage());
            return "抱歉，我暂时无法回复，请稍后再试。";
        }
    }

    // ---- 会话管理 ----

    public SessionManager getSessionManager() { return sessionManager; }

    @Override
    public String getHelpMessage() { return SessionManager.HELP_MESSAGE; }

    /**
     * 无状态对话 — 不写会话历史、不落库。
     * 供工具内部辅助调用（如网页摘要、文档总结），避免污染真实对话上下文。
     */
    @Override
    public String chatDetached(String userId, String userMessage) {
        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addSystemMessage(sessionManager.fullSystemPrompt(sessionManager.getOrCreate(userId)))
                .addUserMessage(userMessage)
                .model(MODEL)
                .build();
            var completion = client.chat().completions().create(params);
            var choices = completion.choices();
            if (choices.isEmpty()) return "（无响应）";
            return choices.get(0).message().content().orElse("（无响应）");
        } catch (Exception e) {
            System.err.println("[AI] ❌ 无状态对话失败: " + e.getMessage());
            return "（无状态对话失败）";
        }
    }

    @Override
    public void record(String userId, String userInput, String assistantOutput) {
        Session session = sessionManager.getOrCreate(userId);
        restoreSession(userId, session);
        session.add("user", userInput);
        session.add("assistant", assistantOutput);
        session.trim(MAX_HISTORY);
        repo.saveMessage(userId, session.name, "user", userInput);
        repo.saveMessage(userId, session.name, "assistant", assistantOutput);
    }

    // ---- 统一 Function Calling ----

    private static final int MAX_FC_ROUNDS = 5; // 最多工具调用轮次，防止无限循环

    @Override
    public String chatWithTools(String userId, String userMessage,
                                List<FunctionDefinition> tools,
                                Map<String, java.util.function.Function<JsonObject, String>> executors) {
        Session session = sessionManager.getOrCreate(userId);
        restoreSession(userId, session);
        session.add("user", userMessage);
        session.trim(MAX_HISTORY);
        repo.saveMessage(userId, session.name, "user", userMessage);

        try {
            // 步骤 1: 构建请求 — 系统提示 + 对话历史 + 全部工具
            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .addSystemMessage(sessionManager.fullSystemPrompt(session));

            for (int i = 0; i < session.roles.size(); i++) {
                if ("user".equals(session.roles.get(i))) {
                    builder.addUserMessage(session.contents.get(i));
                } else {
                    builder.addAssistantMessage(session.contents.get(i));
                }
            }

            for (FunctionDefinition tool : tools) {
                builder.addFunctionTool(tool);
            }

            builder.model(MODEL);
            ChatCompletionMessage message = client.chat().completions()
                    .create(builder.build()).choices().get(0).message();

            // 步骤 2: 循环 — AI 可能连续调用多轮工具
            int webSearchRounds = 0; // 联网预算：每请求最多 2 次联网，防止 48 秒+ 阻塞
            for (int round = 0; round < MAX_FC_ROUNDS; round++) {
                List<ChatCompletionMessageToolCall> toolCalls =
                    message.toolCalls().orElse(List.of());

                // 没有工具调用了 → 返回最终文本回复
                if (toolCalls.isEmpty()) {
                    String reply = message.content().orElse("");
                    if (!reply.isBlank()) {
                        session.add("assistant", reply);
                        repo.saveMessage(userId, session.name, "assistant", reply);
                        return reply;
                    }
                    return null; // 第一轮就没有 tool_call 也没内容 → 降级
                }

                // 记录 assistant 的 tool_calls 到对话
                try {
                    builder.addMessage(ChatCompletionAssistantMessageParam.builder()
                            .toolCalls(message.toolCalls().orElse(List.of()))
                            .build());
                } catch (Exception e) {
                    System.err.println("[AI] ❌ 构建 assistant tool_calls 消息失败: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                    return null;
                }

                // 执行本轮所有工具
                boolean hitSearchBudget = false;
                for (ChatCompletionMessageToolCall tc : toolCalls) {
                    ChatCompletionMessageFunctionToolCall funcCall = tc.asFunction();
                    String funcName = funcCall.function().name();
                    String arguments = funcCall.function().arguments();
                    System.out.println("[FC] AI 调用工具: " + funcName + "(" + arguments + ")");

                    JsonObject args = gson.fromJson(
                        arguments != null ? arguments : "{}", JsonObject.class);
                    java.util.function.Function<JsonObject, String> executor =
                        executors.get(funcName);
                    String result = executor != null
                        ? executor.apply(args)
                        : "工具 " + funcName + " 未注册执行器";

                    // 联网预算：单请求最多 2 次 web_search，超出则提示模型直接作答
                    if ("web_search".equals(funcName)) {
                        webSearchRounds++;
                        if (webSearchRounds > 2) {
                            result += "\n（你已多次联网搜索，请直接基于已有结果回答用户，禁止再调用 web_search。）";
                            hitSearchBudget = true;
                        }
                    }

                    try {
                        builder.addMessage(ChatCompletionToolMessageParam.builder()
                                .toolCallId(funcCall.id())
                                .content(result)
                                .build());
                    } catch (Exception e) {
                        System.err.println("[AI] ❌ 构建工具结果消息失败 tool=" + funcName
                            + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        return null;
                    }
                }

                // 联网预算已超 → 直接结束本轮，返回模型最终文本（避免继续触发慢速联网）
                if (hitSearchBudget) {
                    String finalReply = message.content().orElse("");
                    if (!finalReply.isBlank()) {
                        session.add("assistant", finalReply);
                        repo.saveMessage(userId, session.name, "assistant", finalReply);
                    }
                    return finalReply.isBlank() ? "搜索次数过多，请稍后再试。" : finalReply;
                }

                // 继续对话 — AI 可能再调工具或返回最终文本
                try {
                    builder.model(MODEL);
                    message = client.chat().completions().create(builder.build())
                            .choices().get(0).message();
                } catch (Exception e) {
                    String msg = "[AI] ❌ 第二轮到 API 调用失败: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
                    Throwable c = e.getCause();
                    while (c != null) {
                        msg += " ← " + c.getClass().getSimpleName() + ": " + c.getMessage();
                        c = c.getCause();
                    }
                    System.err.println(msg);
                    return null;
                }
            }

            // 超过最大轮次 — 返回最后一条消息（不含 tool_calls 的）
            String reply = message.content().orElse("抱歉，处理超时，请简化你的请求。");
            session.add("assistant", reply);
            repo.saveMessage(userId, session.name, "assistant", reply);
            return reply;

        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            Throwable cause = e.getCause();
            while (cause != null) {
                msg += " ← " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                cause = cause.getCause();
            }
            System.err.println("[AI] ❌ Function Calling 失败: " + msg);
            return null;
        }
    }
}
