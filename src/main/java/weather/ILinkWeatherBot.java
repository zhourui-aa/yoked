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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

public class ILinkWeatherBot {

    private final WeatherService weatherService;
    private final AiService aiService;
    private ILinkClient client;
    private final ImageService imageService;
    private final VoiceService voiceService;
    private final CommandHandler commandHandler;
    private final ConcurrentHashMap<String, ImageContext> recentImageContext = new ConcurrentHashMap<>();

    public ILinkWeatherBot() {
        this.weatherService = new WeatherService();
        this.aiService = new AiService("qwen-plus");
        this.imageService = new ImageService(this);
        this.voiceService = new VoiceService(this);
        this.commandHandler = new CommandHandler(weatherService, aiService, voiceService);
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
        // 1. 图片追问
        ImageContext ctx = recentImageContext.get(fromUserId);
        if (ctx != null && !ctx.isExpired() && isImageRelatedQuestion(text)) {
            System.out.println("  🔗 检测到图片追问，合并处理");
            String reply = imageService.analyzeImageWithText(fromUserId, ctx.base64Image, text);
            if (reply != null) sendReply(fromUserId, reply);
            recentImageContext.remove(fromUserId);
            return;
        }

        // 2. 清理过期缓存
        recentImageContext.entrySet().removeIf(e -> e.getValue().isExpired());

        // 3. 画图命令
        if (imageService.isDrawCommand(text)) {
            imageService.handleDrawCommand(fromUserId, text);
            return;
        }

        // 4. 其他命令（交给 CommandHandler）
        String reply = commandHandler.handle(text);
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

    // ========== 包内可见，供其他 Service 调用 ==========

    AiService getAiService() {
        return commandHandler.getAiService();
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
        if (client != null) client.close();
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

    // ========== 内部类 ==========

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