package com.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.weather.exception.WeatherException;
import com.weather.model.WeatherResponse;
import com.weather.service.*;
import com.weather.tool.WeatherTool;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class WechatWeatherBot {
    private static WeatherTool weatherTool;
    /** 每个用户的音色偏好 */
    private static final ConcurrentHashMap<String, String> userVoiceMap = new ConcurrentHashMap<>();
    private static final DocumentService documentService = new DocumentService();
    private static final LocationService locationService = new LocationService();
    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(WechatWeatherBot.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static AIClient aiClient = new AIClient();
    private static final ImageAnalyzer imageAnalyzer = new ImageAnalyzer();
    private static final ImageGenerator imageGenerator = new ImageGenerator();
    private static final VoiceService voiceService = new VoiceService();
    /** 已处理的消息 ID，用于 onMessage 和 getUpdates 双路去重 */
    private static final Set<Long> processedMsgIds = ConcurrentHashMap.newKeySet();
    /** 消息缓冲器：收集用户短时间内连续发送的消息 */
    private static final ConcurrentHashMap<String, List<String>> messageBuffer = new ConcurrentHashMap<>();
    /** 定时器表：每个用户一个倒计时 */
    private static final ConcurrentHashMap<String, ScheduledFuture<?>> timerMap = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    /** 缓冲窗口：5秒内无新消息则触发统一处理 */
    private static final long BUFFER_WINDOW_MS = 3000;
    private static WeatherService weatherService;

    public static void main(String[] args) throws Exception {
        try {
            weatherService = new WeatherService();
            log.info("天气服务初始化成功");
            weatherTool = new WeatherTool(weatherService, locationService, objectMapper);
            log.info("天气工具初始化成功");
        } catch (Exception e) {
            log.error("天气服务初始化失败，请检查 application.properties", e);
            System.out.println("天气服务启动失败: " + e.getMessage());
            return;
        }

        final ILinkClient[] clientRef = new ILinkClient[1];

        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfig.builder()
                        .connectTimeoutMs(35000)
                        .readTimeoutMs(35000)
                        .heartbeatEnabled(true)
                        .heartbeatIntervalMs(30000)
                        .build())
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        log.info("登录成功！botId: {}", context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable e) {
                        log.error("登录失败", e);
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            try {
                                handleMessage(clientRef[0], weatherService, aiClient, msg);
                            } catch (Exception e) {
                                System.err.println("[onMessage] 异常: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
                })
                .build();
        clientRef[0] = client;

        // 登录
        String qrContent = client.executeLogin();
        System.out.println("请复制以下链接到浏览器打开，用微信扫码登录：");
        System.out.println(qrContent);

        LoginContext loginCtx = client.getLoginFuture().get();
        System.out.println("登录成功，Bot 已就绪！");

        // ===== 独立轮询线程：每秒拉取一次，保证快速响应 =====
        Thread pollThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<WeixinMessage> messages = clientRef[0].getUpdates();
                    for (WeixinMessage msg : messages) {
                        try {
                            handleMessage(clientRef[0], weatherService, aiClient, msg);
                        } catch (Exception e) {
                            System.err.println("[轮询] handleMessage 异常: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[轮询] getUpdates 异常: " + e.getMessage());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "poll-thread");
        pollThread.setDaemon(true);
        pollThread.start();

        // ===== 定时清理去重缓存，防止内存泄漏 =====
        Thread cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(300_000); // 每 5 分钟
                } catch (InterruptedException e) {
                    break;
                }
                int size = processedMsgIds.size();
                processedMsgIds.clear();
                System.out.println("[清理] 已清空去重缓存（清除 " + size + " 条）");
            }
        }, "cleanup-thread");
        cleanupThread.setDaemon(true);
        cleanupThread.start();

        System.out.println("消息监听已启动 (onMessage推送 + 1秒轮询 + message_id去重)");
        System.out.println("请用微信给 Bot 发消息...");

        // 主线程 keep-alive
        // noinspection InfiniteLoopStatement
        while (true) {
            Thread.sleep(60000);
        }
    }

    private static void trySendText(ILinkClient client, String toUser, String text) {
        try {
            client.sendText(toUser, text);
        } catch (Exception e) {
            System.err.println("[发送失败] " + e.getMessage());
        }
    }

    private static void handleMessage(ILinkClient client,
                                      WeatherService weatherService,
                                      AIClient aiClient,
                                      WeixinMessage msg) throws IOException {

        // ===== message_id 去重：两条路径共用同一个 Set，原子操作保证不重复 =====
        Long msgId = msg.getMessage_id();
        if (msgId != null && !processedMsgIds.add(msgId)) {
            // add() 返回 false = Set 中已存在，说明另一条路径已经处理过了
            return;
        }

        String fromUser = msg.getFrom_user_id();
        System.out.println(">>> 处理消息 msgId=" + msgId + " from=" + fromUser);

        if (msg.getItem_list() == null) {
            System.out.println("!!! item_list 为空");
            return;
        }

        for (MessageItem item : msg.getItem_list()) {
            // ===== 图片消息处理（独立判断，不依赖 text_item） =====
            if (item.getVoice_item() != null) {
                System.out.println("[语音] 收到来自 " + fromUser + " 的语音");
                try {
                    // 第1步：先试微信自带的语音转文字
                    String voiceText = item.getVoice_item().getText();

                    if (voiceText == null || voiceText.isBlank()) {
                        // 第2步：getText() 为空，下载语音做 ASR
                        System.out.println("[语音] getText()为空，下载语音做ASR...");
                        byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);

                        // ★ 注意：这里下载的是 SILK 格式
                        // 需要转成 WAV 才能给百炼 ASR
                        // 见下方"难点说明"
                        voiceText = voiceService.recognize(voiceBytes);
                    }

                    System.out.println("[语音] 识别文字: " + voiceText);

                    // 第3步：把识别出的文字当普通文本消息处理
                    // 复用现有的 callWithTools 流程
                    processText(client, weatherService, aiClient, fromUser, voiceText, true);

                } catch (Exception e) {
                    System.err.println("[语音] 处理失败: " + e.getMessage());
                    e.printStackTrace();
                    trySendText(client, fromUser, "语音处理失败: " + e.getMessage());
                }
                continue;  // 语音消息处理完了，跳过后面的文本逻辑
            }
            if (item.getImage_item() != null) {
                System.out.println("[图片] 收到来自 " + fromUser + " 的图片");
                try {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    String result = imageAnalyzer.analyze(imageBytes, "请详细描述这张图片的内容");
                    System.out.println("[图片] AI 分析结果: " + result);
                    trySendText(client, fromUser, result);
                    aiClient.addToHistory(fromUser, "[用户发了一张图片，图片内容是：" + result + "]", result);
                } catch (Exception e) {
                    System.err.println("[图片] 处理失败: " + e.getMessage());
                    trySendText(client, fromUser, "抱歉，图片识别失败: " + e.getMessage());
                }
                continue;
            }

            // ===== 文件消息处理 =====
            if (item.getFile_item() != null) {
                String fileName = item.getFile_item().getFile_name();
                System.out.println("[文件] 收到来自 " + fromUser + " 的文件: " + fileName);
                try {
                    // 1. 下载文件
                    byte[] fileBytes = client.downloadFileFromMessageItem(item);
                    System.out.println("[文件] 大小: " + fileBytes.length + " bytes");

                    // 2. 解析文档内容
                    String docContent = documentService.parse(fileBytes, fileName);
                    System.out.println("[文件] 解析内容(前200字): "
                            + docContent.substring(0, Math.min(docContent.length(), 200)));

                    // 3. 把文档内容当文本发给 AI 处理
                    //    构造消息：告诉 AI 用户发了一个文档，内容是什么
                    String messageForAI = "[用户发送了文件: " + fileName + "]\n文档内容:\n" + docContent;
                    processText(client, weatherService, aiClient, fromUser, messageForAI, false);

                } catch (Exception e) {
                    System.err.println("[文件] 处理失败: " + e.getMessage());
                    e.printStackTrace();
                    trySendText(client, fromUser, "文件处理失败: " + e.getMessage());
                }
                continue;  // 文件消息处理完了，跳过后面的文本逻辑
            }

            // ===== 文本消息处理 =====
            if (item.getText_item() == null) {
                continue;
            }

            String text = item.getText_item().getText().trim();

            // ====== 新增：图片生成关键词前置判断 ======
            List<String> buffer = messageBuffer.computeIfAbsent(fromUser, k -> new ArrayList<>());
            buffer.add(text);
            System.out.println("[缓冲] 用户 " + fromUser + " 当前缓冲 " + buffer.size() + " 条消息");

// 取消旧的定时器（每次新消息重置倒计时）
            ScheduledFuture<?> oldTimer = timerMap.remove(fromUser);
            if (oldTimer != null) {
                oldTimer.cancel(false);
            }

// 启动新的倒计时
            ScheduledFuture<?> newTimer = scheduler.schedule(() -> {
                flushBuffer(client, aiClient, fromUser);
            }, BUFFER_WINDOW_MS, TimeUnit.MILLISECONDS);
            timerMap.put(fromUser, newTimer);

        }
    }
    /**
     * 处理文本消息（从 handleMessage 和语音处理共用）
     */
    private static void processText(ILinkClient client,
                                    WeatherService weatherService,
                                    AIClient aiClient,
                                    String fromUser, String text,
                                    boolean fromVoice) {
        // ===== "用XX音色+动词+内容" → 临时音色模式 =====
// 例: "用温柔女声发送今天天气" → actualText="今天天气", tempVoice="longanhuan_v3"
        String tempVoice = null;
        String actualText = text;

        // 清洗：去掉常见前缀和引号，如 "在"用这个声音 → 用这个声音
        String cleanedText = text.replaceAll("^[在再那就\"'\"「『]+", "").trim();

        java.util.regex.Pattern voiceUsePattern = java.util.regex.Pattern.compile(
                "^(用|换)(.+?)(?:给我|帮我)?(发送|说|读|念|告诉|播报|回复|讲|发)(.+)$");
        java.util.regex.Matcher voiceUseMatcher = voiceUsePattern.matcher(text);
        if (voiceUseMatcher.find()) {
            String voiceHint = voiceUseMatcher.group(2).trim();
            actualText = voiceUseMatcher.group(4).trim();

            // 代词解析：这个声音/这个音色 → 用已保存的
            if ("这个声音".equals(voiceHint) || "这个音色".equals(voiceHint)
                    || "当前音色".equals(voiceHint) || "刚才的声音".equals(voiceHint)) {
                tempVoice = userVoiceMap.get(fromUser);
                if (tempVoice != null) {
                    System.out.println("[音色] 代词解析: 使用已保存的 " + tempVoice
                            + " (" + VoiceService.SUPPORTED_VOICES.get(tempVoice) + ")");
                } else {
                    System.out.println("[音色] 代词解析: 用户未设置过音色，使用默认");
                }
            } else {
                tempVoice = fuzzyMatchVoice(voiceHint);
            }

            if (tempVoice != null) {
                System.out.println("[音色] 临时使用: " + tempVoice
                        + " (" + VoiceService.SUPPORTED_VOICES.get(tempVoice) + ")");
            } else {
                actualText = text;  // 音色没匹配到，整句给AI
            }
        }
        System.out.println(">>> 文本: [" + text + "]");
        // ===== 音色切换指令 =====
        if (text.startsWith("切换音色 ") || text.startsWith("换音色 ") || text.startsWith("换成")) {
            String target = text.replace("切换音色 ", "").replace("换音色 ", "")
                    .replace("换成", "").trim();
            handleVoiceSwitch(client, fromUser, target);
            return;
        }
        if (text.trim().equals("音色列表")) {
            StringBuilder sb = new StringBuilder("可选音色:\n");
            VoiceService.SUPPORTED_VOICES.forEach((k, v) ->
                    sb.append(k).append(" - ").append(v).append("\n"));
            trySendText(client, fromUser, sb.toString());
            return;
        }

        String messageForAI;
        if (fromVoice) {
            messageForAI = "[语音消息] " + actualText;
        } else if (tempVoice != null) {
            messageForAI = "[用户要求语音回答] " + actualText;
        } else {
            messageForAI = actualText;
        }

        String reply;
        try {
            // 统一入口：带工具清单调用 AI
            AIClient.AICallResult result = aiClient.callWithTools(fromUser, messageForAI);

            if (result.hasToolCall) {
                // AI 要求调用工具
                System.out.println(">>> 工具调用: " + result.toolName
                        + " 参数: " + result.toolArguments);

                String toolResult;
                if ("query_weather".equals(result.toolName)) {
                    // 从参数 JSON 里提取 city
                /*    JsonNode args = objectMapper.readTree(result.toolArguments);
                    String city = args.path("city").asText().trim();
                    if(city.isEmpty()){
                        System.out.println("用户未指定城市，自动定位中...");
                        try {
                            city = locationService.getCurrentCity();
                            System.out.println(">>> 自动定位城市: " + city);
                        } catch (Exception e) {
                            System.err.println(">>> 自动定位失败: " + e.getMessage());
                            city = "北京";  // 定位失败用默认城市
                        }
                    }
                    System.out.println(">>> 查询天气: " + city);

                    WeatherResponse weather = weatherService.queryByCity(city);
                    toolResult = String.format(
                            "城市:%s 温度:%s°C 体感:%s°C 天气:%s 风向:%s %s级 湿度:%s%% 气压:%shPa",
                            weather.getCityName(), weather.getNow().getTemp(),
                            weather.getNow().getFeelsLike(), weather.getNow().getText(),
                            weather.getNow().getWindDir(), weather.getNow().getWindScale(),
                            weather.getNow().getHumidity(), weather.getNow().getPressure()
                    );
                 */
                    toolResult = weatherTool.execute(result.toolArguments);
                }else if ("generate_image".equals(result.toolName)) {
                    // 提取图片描述
                    JsonNode args = objectMapper.readTree(result.toolArguments);
                    String imageDesc = args.path("description").asText().trim();
                    if (imageDesc.isEmpty()) {
                        imageDesc = "美丽的风景画";
                    }
                    System.out.println("[图片生成] 描述: " + imageDesc);
                    try {
                        trySendText(client, fromUser, "正在为你生成图片，请稍等...");
                        byte[] imageBytes = imageGenerator.generate(imageDesc);
                        client.sendImage(fromUser, imageBytes, "generated.png", null);
                        System.out.println("[图片生成] 已发送");
                    } catch (Exception e) {
                        System.err.println("[图片生成] 失败: " + e.getMessage());
                        trySendText(client, fromUser, "图片生成失败: " + e.getMessage());
                    }
                    aiClient.addToHistory(fromUser, messageForAI, "已为用户生成了一张图片：" + imageDesc);
                    return;  // 图片生成完直接返回，不走后面的文字回复
                } else if ("generate_document".equals(result.toolName)) {
                    // 提取参数
                    JsonNode args = objectMapper.readTree(result.toolArguments);
                    String content = args.path("content").asText();
                    String format = args.path("format").asText().trim();
                    if (format.isEmpty()) format = "txt";
                    String fileName = args.path("file_name").asText().trim();
                    if (fileName.isEmpty()) fileName = "document";

                    System.out.println("[文档生成] 格式: " + format + ", 文件名: " + fileName);
                    try {
                        // 1. 生成文档
                        byte[] docBytes = documentService.generate(content, format);

                        // 2. 发送文件给用户
                        String fullFileName = fileName + "." + format;
                        String mimeType = DocumentService.getMimeType(format);
                        client.sendFile(fromUser, docBytes, fullFileName, null);
                        System.out.println("[文档生成] 已发送: " + fullFileName);

                        // 3. 存入历史记录，防止失忆
                        aiClient.addToHistory(fromUser, messageForAI,
                                "已为用户生成了" + format.toUpperCase() + "文档: " + fullFileName);
                    } catch (Exception e) {
                        System.err.println("[文档生成] 失败: " + e.getMessage());
                        e.printStackTrace();
                        trySendText(client, fromUser, "文档生成失败: " + e.getMessage());
                    }
                    return;  // 文档生成完直接返回

                } else {
                    toolResult = "未知工具: " + result.toolName;
                }

                // 把工具结果发回 AI，让它生成自然语言回复
                reply = aiClient.callWithToolResult(
                        fromUser, messageForAI,
                        result.toolName, result.toolArguments,
                        result.toolCallId, toolResult
                );

            } else {
                // 普通闲聊，直接回复
                reply = result.text;
            }

        } catch (WeatherException e) {
            reply = "抱歉，" + e.getMessage();
        } catch (Exception e) {
            System.err.println("!!! 处理异常: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
            e.printStackTrace();
            reply = "抱歉，出了点问题，请稍后再试。";
        }

            // ===== 检查是否需要语音回复 =====
        boolean useVoice = false;
        if (reply.startsWith("[voice]")) {
            useVoice = true;
            reply = reply.substring("[voice]".length()).trim();
        } else if (reply.startsWith("[text]")) {
            useVoice = false;
            reply = reply.substring("[text]".length()).trim();
        } else {
            // AI 没加标记 → 用 fromVoice 兜底
            // 语音消息默认语音回复，但如果回复含大量数字则降级文字
            useVoice = fromVoice && !reply.matches(".*\\d{2,}.*");
            System.out.println(">>> [兜底] AI未加标记，用fromVoice判断: " + useVoice);
        }

        // 强制语音：用户显式指定了音色
        if (tempVoice != null) {
            useVoice = true;
        }

        System.out.println(">>> 回复: " + reply + " (语音=" + useVoice + ")");

        if (useVoice) {
            try {
                // 获取该用户的音色偏好
                String voice = tempVoice != null ? tempVoice
                        : userVoiceMap.getOrDefault(fromUser, VoiceService.DEFAULT_VOICE);
                System.out.println("[语音] 使用音色: " + voice
                        + " (" + VoiceService.SUPPORTED_VOICES.getOrDefault(voice, "未知") + ")");
                byte[] wavBytes = voiceService.synthesizeToWav(reply, voice);
                // 直接发送 WAV 语音文件
                System.out.println("[语音] TTS合成成功, WAV大小: " + wavBytes.length + " bytes");
                client.sendFile(fromUser, wavBytes, "reply.wav", null);
                System.out.println("[语音] 已发送 WAV 语音文件");
            } catch (Exception e) {
                trySendText(client, fromUser, "[语音失败] " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                e.printStackTrace();
            }
        } else {
            trySendText(client, fromUser, reply);
        }
    }

    /**
     * 倒计时到期后，将缓冲消息统一发送给 AI 处理
     */
    private static void flushBuffer(ILinkClient client, AIClient aiClient, String fromUser) {
        List<String> buffer = messageBuffer.remove(fromUser);
        timerMap.remove(fromUser);

        if (buffer == null || buffer.isEmpty()) return;

        System.out.println("[缓冲] 用户 " + fromUser + " 缓冲窗口到期，合并 " + buffer.size() + " 条消息");

        if (buffer.size() == 1) {
            // 只有一条消息，直接处理
            processText(client, weatherService, aiClient, fromUser, buffer.get(0), false);
        } else {
            // 多条消息，合并成一条综合请求
            StringBuilder sb = new StringBuilder();
            sb.append("[用户连续发送了以下 ").append(buffer.size()).append(" 条消息]\n");
            for (int i = 0; i < buffer.size(); i++) {
                sb.append(i + 1).append(". ").append(buffer.get(i)).append("\n");
            }
            sb.append("\n请综合理解以上所有消息的意图，生成一条统一的回复。");
            processText(client, weatherService, aiClient, fromUser, sb.toString(), false);
        }
    }

    /**
     * 根据用户输入模糊匹配音色 key
     * @return 匹配到的 voice key，没匹配到返回 null
     */
    private static String fuzzyMatchVoice(String hint) {
        if (hint == null || hint.isBlank()) return null;
        // 1. 精确匹配参数名
        if (VoiceService.isValidVoice(hint)) return hint;
        // 2. 完整匹配描述
        for (Map.Entry<String, String> entry : VoiceService.SUPPORTED_VOICES.entrySet()) {
            if (entry.getValue().contains(hint) || entry.getKey().contains(hint)) {
                return entry.getKey();
            }
        }
        // 3. 前缀退格匹配：处理 "女音给我" → 先试 "女音给我" → "女音给" → "女音" → "女" → 匹配到 "女"
        for (int len = hint.length(); len >= 1; len--) {
            String prefix = hint.substring(0, len);
            for (Map.Entry<String, String> entry : VoiceService.SUPPORTED_VOICES.entrySet()) {
                if (entry.getValue().contains(prefix) || entry.getKey().contains(prefix)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * 处理音色切换
     */
    private static void handleVoiceSwitch(ILinkClient client, String fromUser, String target) {
        // 1. 先尝试精确匹配 voice 参数名
        if (VoiceService.isValidVoice(target)) {
            userVoiceMap.put(fromUser, target);
            String desc = VoiceService.SUPPORTED_VOICES.get(target);
            trySendText(client, fromUser, "已切换到音色: " + desc + " (" + target + ")");
            System.out.println("[音色] 用户 " + fromUser + " 切换到: " + target);
            return;
        }

        String matched = fuzzyMatchVoice(target);
        if (matched != null) {
            userVoiceMap.put(fromUser, matched);
            trySendText(client, fromUser, "已切换到音色: " + VoiceService.SUPPORTED_VOICES.get(matched));
            System.out.println("[音色] 用户 " + fromUser + " 切换到: " + matched);
            return;
        }

        // 3. 没匹配到，提示
        trySendText(client, fromUser, "未找到音色「" + target + "」，发送'音色列表'查看所有可用音色");
    }
}
