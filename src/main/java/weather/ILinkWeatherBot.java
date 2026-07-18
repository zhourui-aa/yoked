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
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class ILinkWeatherBot {

    private final WeatherService weatherService;
    private AiService aiService;
    private ILinkClient client;
    private final ImageService imageService;
    private final VoiceService voiceService;
    private final ConcurrentHashMap<String, ImageContext> recentImageContext = new ConcurrentHashMap<>();

    private static final Map<String, String> AVAILABLE_MODELS = new HashMap<>();
    static {
        AVAILABLE_MODELS.put("qwen-plus", "通义千问-Plus（均衡推荐）");
        AVAILABLE_MODELS.put("qwen-max", "通义千问-Max（最强能力）");
        AVAILABLE_MODELS.put("qwen-turbo", "通义千问-Turbo（快速便宜）");
        AVAILABLE_MODELS.put("qwen-vl-plus", "通义千问-VL（图片识别）");
        AVAILABLE_MODELS.put("deepseek-v4-flash", "DeepSeek-V4-Flash（阿里直供）");
        AVAILABLE_MODELS.put("deepseek-v4-pro", "DeepSeek-V4-Pro（阿里直供）");
        AVAILABLE_MODELS.put("kimi-k2.7-code", "Kimi-K2.7-Code（阿里直供）");
        AVAILABLE_MODELS.put("kimi-k2.6", "Kimi-K2.6（阿里直供）");
        AVAILABLE_MODELS.put("glm-5.2", "智谱 GLM-5.2（1M上下文/编程强）");
        AVAILABLE_MODELS.put("wan2.7-image", "通义万相 2.7（文生图）");
        AVAILABLE_MODELS.put("wan2.7-image-pro", "通义万相 2.7 Pro（高质量）");
    }

    public ILinkWeatherBot() {
        this.weatherService = new WeatherService();
        this.aiService = new AiService("qwen-plus");
        this.imageService = new ImageService(this);
        this.voiceService = new VoiceService(this);
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

                if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    if (text != null) {
                        text = text.trim();
                        System.out.println("  💬 文本: [" + text + "]");
                        handleTextMessage(fromUserId, text);
                    }
                } else if (item.getImage_item() != null) {
                    System.out.println("  🖼️ 图片消息");
                    String imageBase64 = imageService.handleImageMessageAndReturnBase64(fromUserId, item);
                    if (imageBase64 != null) {
                        recentImageContext.put(fromUserId, new ImageContext(imageBase64));
                        System.out.println("  📝 已缓存图片上下文，等待用户提问...");
                        // 不再自动分析，等用户发文字追问时再调用视觉模型
                    }

                } else if (item.getVoice_item() != null) {
                    System.out.println("  🎤 语音消息");
                    String voiceText = item.getVoice_item().getText();
                    voiceService.handleVoiceMessage(fromUserId, voiceText);
                } else if (item.getFile_item() != null) {
                    System.out.println("  📎 文件消息");
                    String fileName = item.getFile_item().getFile_name();
                    sendReply(fromUserId, "📎 收到文件: " + fileName + "，暂时无法处理~");
                } else if (item.getVideo_item() != null) {
                    System.out.println("  🎬 视频消息");
                    sendReply(fromUserId, "🎬 收到视频，暂时无法处理~");
                } else {
                    System.out.println("  ❓ 未知消息类型");
                }
            }
        }
    }

    void handleTextMessage(String fromUserId, String text) {
        // 1. 检查是否是图片追问
        ImageContext ctx = recentImageContext.get(fromUserId);
        if (ctx != null && !ctx.isExpired() && isImageRelatedQuestion(text)) {
            System.out.println("  🔗 检测到图片追问，合并处理");
            String reply = imageService.analyzeImageWithText(fromUserId, ctx.base64Image, text);
            if (reply != null) {
                sendReply(fromUserId, reply);
            }
            recentImageContext.remove(fromUserId); // 用完清除，避免重复关联
            return;
        }

        // 2. 清理过期缓存
        recentImageContext.entrySet().removeIf(e -> e.getValue().isExpired());

        // 3. 原有逻辑不变
        if (imageService.isDrawCommand(text)) {
            imageService.handleDrawCommand(fromUserId, text);
            return;
        }

        String reply = handleModelCommand(text);
        if (reply == null) {
            reply = processCommand(text);
        }
        if (reply != null) {
            sendReply(fromUserId, reply);
        }
    }

    private boolean isImageRelatedQuestion(String text) {
        String lower = text.toLowerCase();
        String[] keywords = {"图片", "图", "照片", "这张", "这个", "什么", "分析", "描述", "里面", "上面", "看下", "看看"};
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
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

        if (lower.equals("语音回复 开") || lower.equals("语音开启")) {
            voiceService.setVoiceReplyEnabled(true);
            return "🔊 语音回复已开启";
        }
        if (lower.equals("语音回复 关") || lower.equals("语音关闭")) {
            voiceService.setVoiceReplyEnabled(false);
            return "🔇 语音回复已关闭";
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
                    "🎨 画 xxx → AI生成图片\n" +
                    "💬 任意文字 → AI对话\n" +
                    "📋 模型列表 → 查看可用模型\n" +
                    "🔧 模型 xxx → 切换模型\n" +
                    "📌 当前模型 → 查看当前模型\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "💡 图片识别需切换至 qwen-vl-plus\n" +
                    "💡 画图使用: 画 一只橘猫在月球上\n" +
                    "💡 画图模型: wan2.7-image / wan2.7-image-pro";
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

    private String extractMediaUrl(Object mediaItem) {
        if (mediaItem == null) return null;
        try {
            Object media;
            try {
                Method getMedia = mediaItem.getClass().getMethod("getMedia");
                media = getMedia.invoke(mediaItem);
            } catch (NoSuchMethodException e) {
                media = mediaItem;
            }
            if (media == null) {
                System.out.println("  🔍 getMedia() 返回 null");
                return null;
            }
            Class<?> clazz = media.getClass();
            System.out.println("  🔍 CDNMedia 类型: " + clazz.getName());
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

    AiService getAiService() {
        return aiService;
    }

    ILinkClient getClient() {
        return client;
    }

    void sendReply(String toUserId, String message) {
        if (message == null || toUserId == null) return;

        if (voiceService.isVoiceReplyEnabled() && message.length() < 300) {
            boolean sent = voiceService.trySendVoice(toUserId, message);
            if (!sent) {
                try {
                    client.sendText(toUserId, message);
                    System.out.println("✅ 文字 fallback 发送成功");
                } catch (Exception e) {
                    System.err.println("❌ 文字发送也失败: " + e.getMessage());
                }
            }
        } else {
            try {
                client.sendText(toUserId, message);
                System.out.println("✅ 回复成功");
            } catch (Exception e) {
                System.err.println("❌ 发送失败: " + e.getMessage());
            }
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

    private static class ImageContext {
        final String base64Image;
        final Instant time;

        ImageContext(String base64Image) {
            this.base64Image = base64Image;
            this.time = Instant.now();
        }

        boolean isExpired() {
            return Instant.now().isAfter(time.plusSeconds(30));
        }
    }
}