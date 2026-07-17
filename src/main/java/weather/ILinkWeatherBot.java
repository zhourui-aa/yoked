package weather;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ILinkWeatherBot {

    private final WeatherService weatherService;
    private AiService aiService;
    private ILinkClient client;

    // 可用模型列表
    private static final Map<String, String> AVAILABLE_MODELS = new HashMap<>();
    static {
        AVAILABLE_MODELS.put("qwen-plus", "通义千问-Plus（均衡推荐）");
        AVAILABLE_MODELS.put("qwen-max", "通义千问-Max（最强能力）");
        AVAILABLE_MODELS.put("qwen-turbo", "通义千问-Turbo（快速便宜）");
        AVAILABLE_MODELS.put("qwen-coder-plus", "通义千问-Coder（编程专用）");
        AVAILABLE_MODELS.put("qwen-vl-plus", "通义千问-VL（图片识别）");
        AVAILABLE_MODELS.put("deepseek-v4-flash", "DeepSeek-V4-Flash（阿里直供）");
        AVAILABLE_MODELS.put("deepseek-v4-pro", "DeepSeek-V4-Pro（阿里直供）");
        AVAILABLE_MODELS.put("kimi-k2.7-code", "Kimi-K2.7-Code（阿里直供）");
        AVAILABLE_MODELS.put("kimi-k2.6", "Kimi-K2.6（阿里直供）");
    }

    public ILinkWeatherBot() {
        this.weatherService = new WeatherService();
        this.aiService = new AiService("qwen-plus");
    }

    public void start() throws Exception {
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .heartbeatEnabled(true)
                .build();

        this.client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        System.out.println("✅ 登录成功！botId = " + context.getBotId());
                        System.out.println("🤖 AI 天气机器人已启动");
                        System.out.println("👉 发送 help 查看所有命令");
                    }
                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("❌ 登录失败: " + throwable.getMessage());
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        handleMessages(messages);
                    }
                })
                .build();

        System.out.println("请扫码登录：");
        String qrCode = client.executeLogin();
        System.out.println(qrCode);

        LoginContext context = client.getLoginFuture().get();
        System.out.println("🎉 登录完成！");

        while (!client.isStopped()) {
            Thread.sleep(1000);
        }
    }

    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            String fromUserId = msg.getFrom_user_id();
            System.out.println("📥 收到消息 from: " + fromUserId);

            if (msg.getItem_list() == null) {
                System.out.println("⚠️ item_list 为空");
                continue;
            }

            System.out.println("📦 包含 " + msg.getItem_list().size() + " 个 item");

            for (int i = 0; i < msg.getItem_list().size(); i++) {
                MessageItem item = msg.getItem_list().get(i);
                System.out.println("  📄 Item " + i + " type=" + item.getType());

                // ========== 1. 文本消息 ==========
                if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    if (text != null) {
                        text = text.trim();
                        System.out.println("  💬 文本: [" + text + "]");
                        handleTextMessage(fromUserId, text);
                    }
                }

                // ========== 2. 图片消息 ==========
                else if (item.getImage_item() != null) {
                    System.out.println("  🖼️ 图片消息");

                    try {
                        // 使用新的 downloadImage 方法，自动处理 aeskey 优先级
                        byte[] imageBytes = MediaDownloader.downloadImage(item.getImage_item());
                        String dataUri = MediaDownloader.toDataUri(imageBytes, "image/jpeg");

                        System.out.println("  🖼️ 图片已解密，Data URI: " + dataUri.substring(0, Math.min(60, dataUri.length())) + "...");

                        // 调用 AI 分析
                        String analysis = aiService.analyzeImage(dataUri, "请详细描述这张图片的内容");
                        sendReply(fromUserId, "🖼️ 图片分析：\n" + analysis);

                    } catch (Exception e) {
                        System.err.println("❌ 图片处理失败: " + e.getMessage());
                        e.printStackTrace();
                        sendReply(fromUserId, "❌ 图片处理失败: " + e.getMessage());
                    }
                }
                // ========== 3. 语音消息 ==========
                else if (item.getVoice_item() != null) {
                    System.out.println("  🎤 语音消息");
                    String voiceUrl = extractMediaUrl(item.getVoice_item());
                    System.out.println("  🎤 URL: " + (voiceUrl != null ? voiceUrl.substring(0, Math.min(50, voiceUrl.length())) : "null"));

                    String voiceText = item.getVoice_item().getText();
                    if (voiceText != null && !voiceText.isEmpty()) {
                        System.out.println("  🎤 语音转文字: [" + voiceText + "]");
                        handleTextMessage(fromUserId, voiceText);
                    } else {
                        sendReply(fromUserId, "🎤 收到语音消息，暂时无法处理语音内容~");
                    }
                }

                // ========== 4. 文件消息 ==========
                else if (item.getFile_item() != null) {
                    System.out.println("  📎 文件消息");
                    String fileName = item.getFile_item().getFile_name();
                    sendReply(fromUserId, "📎 收到文件: " + fileName + "，暂时无法处理~");
                }

                // ========== 5. 视频消息 ==========
                else if (item.getVideo_item() != null) {
                    System.out.println("  🎬 视频消息");
                    sendReply(fromUserId, "🎬 收到视频，暂时无法处理~");
                }

                else {
                    System.out.println("  ❓ 未知消息类型");
                }
            }
        }
    }

    /**
     * 🔧 核心修复：智能提取媒体 URL
     * 兼容 SDK 中 CDNMedia 没有 getUrl() 的情况
     */
    private String extractMediaUrl(Object mediaItem) {
        if (mediaItem == null) {
            return null;
        }

        try {
            // 步骤1: 从 VoiceItem/ImageItem 获取 getMedia()
            Object media;
            try {
                Method getMedia = mediaItem.getClass().getMethod("getMedia");
                media = getMedia.invoke(mediaItem);
            } catch (NoSuchMethodException e) {
                media = mediaItem; // 本身就是 media 对象
            }

            if (media == null) {
                System.out.println("  🔍 getMedia() 返回 null");
                return null;
            }

            Class<?> clazz = media.getClass();
            System.out.println("  🔍 CDNMedia 类型: " + clazz.getName());

            // 步骤2: 尝试所有无参 String 方法（名字含 url/cdn）
            for (Method method : clazz.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == String.class) {
                    String name = method.getName().toLowerCase();
                    if (name.contains("url") || name.contains("cdn") || name.contains("link") || name.contains("path")) {
                        try {
                            String value = (String) method.invoke(media);
                            if (value != null && !value.isEmpty() && value.length() > 5) {
                                System.out.println("  ✅ 通过方法 " + method.getName() + "() 提取到 URL");
                                return value;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // 步骤3: 尝试所有 String 字段
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType() == String.class) {
                    field.setAccessible(true);
                    try {
                        String value = (String) field.get(media);
                        if (value != null && !value.isEmpty() && value.length() > 5) {
                            System.out.println("  ✅ 通过字段 " + field.getName() + " 提取到 URL");
                            return value;
                        }
                    } catch (Exception ignored) {}
                }
            }

            // 步骤4: 调试输出所有字段值
            System.err.println("  🔍 CDNMedia 所有字段（调试）:");
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    System.err.println("    " + field.getName() + " (" + field.getType().getSimpleName() + ") = " + field.get(media));
                } catch (Exception e) {
                    System.err.println("    " + field.getName() + " = [无法读取]");
                }
            }

        } catch (Exception e) {
            System.err.println("  ❌ 提取 URL 异常: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    private void handleTextMessage(String fromUserId, String text) {
        String reply = handleModelCommand(text);
        if (reply == null) {
            reply = processCommand(text);
        }
        if (reply != null) {
            sendReply(fromUserId, reply);
        }
    }

    private void handleImageMessage(String fromUserId, String imageUrl) {
        try {
            String analysis = aiService.analyzeImage(imageUrl, "请详细描述这张图片的内容");
            sendReply(fromUserId, "🖼️ 图片分析：\n" + analysis);
        } catch (Exception e) {
            System.err.println("❌ 图片分析失败: " + e.getMessage());
            e.printStackTrace();
            sendReply(fromUserId, "❌ 图片分析失败: " + e.getMessage() + "\n请确保已切换到视觉模型（发送：模型 qwen-vl-plus）");
        }
    }

    private String handleModelCommand(String text) {
        String lower = text.toLowerCase().trim();

        if (lower.equals("模型列表") || lower.equals("models")) {
            StringBuilder sb = new StringBuilder();
            sb.append("📋 可用模型列表（").append(AVAILABLE_MODELS.size()).append("个）\n");
            sb.append("━━━━━━━━━━━━━━━\n");
            for (Map.Entry<String, String> entry : AVAILABLE_MODELS.entrySet()) {
                sb.append("• ").append(entry.getKey()).append("\n");
                sb.append("  → ").append(entry.getValue()).append("\n");
            }
            sb.append("━━━━━━━━━━━━━━━\n");
            sb.append("💡 发送「模型 名称」切换\n");
            sb.append("💡 例：模型 qwen-vl-plus");
            return sb.toString();
        }

        if (lower.equals("当前模型") || lower.equals("model")) {
            return "🤖 当前模型: " + aiService.getModelName() + "\n发送「模型列表」查看所有模型";
        }

        if (lower.startsWith("模型 ") || lower.startsWith("model ")) {
            String modelName = lower.startsWith("模型 ") ? text.substring(3).trim() : text.substring(6).trim();
            if (!AVAILABLE_MODELS.containsKey(modelName)) {
                return "❌ 未知模型: " + modelName + "\n发送「模型列表」查看可用模型";
            }
            try {
                aiService = new AiService(modelName);
                return "✅ 已切换到模型: " + modelName + "\n描述: " + AVAILABLE_MODELS.get(modelName);
            } catch (Exception e) {
                return "❌ 切换失败: " + e.getMessage();
            }
        }

        return null;
    }

    private String processCommand(String text) {
        String lower = text.toLowerCase().trim();

        if (lower.equals("help") || lower.equals("帮助")) {
            return "🤖 AI 天气机器人\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "🌤️ 天气 北京 → 查询天气\n" +
                    "🖼️ 发送图片 → AI识别图片\n" +
                    "💬 任意文字 → AI对话\n" +
                    "📋 模型列表 → 查看可用模型\n" +
                    "🔧 模型 xxx → 切换模型\n" +
                    "📌 当前模型 → 查看当前模型\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "💡 图片识别需切换至 qwen-vl-plus";
        }

        if (lower.startsWith("天气 ")) {
            return queryWeather(text.substring(3).trim(), false);
        }
        if (lower.startsWith("weather ")) {
            return queryWeather(text.substring(8).trim(), false);
        }
        if (lower.contains("天气") && (lower.contains("怎么样") || lower.contains("如何"))) {
            String city = extractCityFromText(text);
            if (city != null) return queryWeather(city, true);
        }

        return chatWithAi(text);
    }

    private String queryWeather(String city, boolean useAi) {
        System.out.println("🌤️ 查询天气: " + city + (useAi ? " (AI分析)" : ""));
        if (city == null || city.isEmpty()) {
            return "❌ 请提供城市名称";
        }
        try {
            WeatherInfo info = weatherService.getCurrentWeather(city);
            if (useAi) {
                return aiService.analyzeWeather(city, info.format());
            } else {
                return info.format();
            }
        } catch (Exception e) {
            System.err.println("❌ 天气查询失败: " + e.getMessage());
            return "❌ 天气查询失败: " + e.getMessage();
        }
    }

    private String chatWithAi(String message) {
        System.out.println("🧠 AI对话: " + message);
        try {
            return aiService.chat(message);
        } catch (Exception e) {
            System.err.println("❌ AI对话失败: " + e.getMessage());
            return "❌ AI服务暂时不可用: " + e.getMessage();
        }
    }

    private String extractCityFromText(String text) {
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "西安", "重庆",
                "天津", "苏州", "长沙", "郑州", "沈阳", "青岛", "宁波", "东莞", "无锡", "佛山"};
        for (String city : cities) {
            if (text.contains(city)) return city;
        }
        return null;
    }

    private void sendReply(String toUserId, String message) {
        if (message == null || toUserId == null) return;
        try {
            client.sendText(toUserId, message);
            System.out.println("✅ 回复成功");
        } catch (Exception e) {
            System.err.println("❌ 发送失败: " + e.getMessage());
        }
    }

    public void stop() {
        System.out.println("🛑 正在关闭...");
        if (client != null) {
            client.close();
        }
    }

    public static void main(String[] args) {
        ILinkWeatherBot bot = new ILinkWeatherBot();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n收到关闭信号...");
            bot.stop();
        }));
        try {
            bot.start();
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}