package weather;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.FileItem;
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
    private final DocumentService documentService;
    private final ConcurrentHashMap<String, ImageContext> recentImageContext = new ConcurrentHashMap<>();

    public ILinkWeatherBot() {
        this.weatherService = new WeatherService();
        this.aiService = new AiService("qwen-plus");
        this.imageService = new ImageService(this);
        this.voiceService = new VoiceService(this);
        this.commandHandler = new CommandHandler(weatherService, aiService, voiceService);
        this.documentService = new DocumentService(this);
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
                // ===== 关键修复：每个 item 独立 try-catch =====
                try {
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

                        // 打印 FileItem 的所有方法
                        System.out.println("  🔍 FileItem 所有方法:");
                        for (Method m : item.getFile_item().getClass().getMethods()) {
                            System.out.println("    " + m.getName() + " (参数:" + m.getParameterCount() +
                                    ", 返回:" + m.getReturnType().getSimpleName() + ")");
                        }

                        documentService.handleDocumentMessage(fromUserId, item);
                    } else if (item.getVideo_item() != null) {
                        System.out.println("  🎬 视频消息");
                        sendReply(fromUserId, "🎬 收到视频，暂时无法处理~");
                    } else {
                        System.out.println("  ❓ 未知消息类型");
                    }
                } catch (Throwable e) {
                    System.err.println("❌ 处理 Item " + i + " 异常: " + e.getMessage());
                    e.printStackTrace();
                    // 单个 item 失败不影响其他 item
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

    String extractMediaUrl(Object mediaItem) {
        System.out.println("  🔍 提取 URL，对象类型: " + mediaItem.getClass().getName());
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

            // ===== 新增：打印所有方法 =====
            System.out.println("  🔍 CDNMedia 所有方法:");
            for (Method m : clazz.getMethods()) {
                System.out.println("    " + m.getName() + " (参数:" + m.getParameterCount() +
                        ", 返回:" + m.getReturnType().getSimpleName() + ")");
            }

            String encryptParam = null;
            String realUrl = null;

            for (Method method : clazz.getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == String.class) {
                    String name = method.getName().toLowerCase();
                    try {
                        String value = (String) method.invoke(media);
                        if (value != null && !value.isEmpty() && value.length() > 5) {
                            // 如果是 encrypt_query_param，记下来稍后拼接
                            if (name.contains("encrypt")) {
                                encryptParam = value;
                                System.out.println("  📝 提取到加密参数: " + value.substring(0, Math.min(30, value.length())) + "...");
                            }
                            // 如果是完整 URL（以 http 开头），直接返回
                            else if (value.startsWith("http")) {
                                System.out.println("  ✅ 通过方法 " + method.getName() + "() 提取到完整 URL");
                                return value;
                            }
                            // 其他可能是路径或 URL
                            else if (name.contains("url") || name.contains("cdn") || name.contains("link") || name.contains("path")) {
                                realUrl = value;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType() == String.class) {
                    field.setAccessible(true);
                    try {
                        String value = (String) field.get(media);
                        if (value != null && !value.isEmpty() && value.length() > 5) {
                            if (field.getName().toLowerCase().contains("encrypt")) {
                                encryptParam = value;
                                System.out.println("  📝 提取到加密参数(字段): " + value.substring(0, Math.min(30, value.length())) + "...");
                            } else if (value.startsWith("http")) {
                                System.out.println("  ✅ 通过字段 " + field.getName() + " 提取到完整 URL");
                                return value;
                            } else {
                                realUrl = value;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            // 如果拿到的是加密参数，拼接成完整下载 URL
            if (encryptParam != null) {
                String fullUrl = "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=" + encryptParam;
                System.out.println("  🔗 拼接文件下载 URL: " + fullUrl.substring(0, Math.min(80, fullUrl.length())) + "...");
                return fullUrl;
            }

            // 兜底：返回提取到的任何 URL
            if (realUrl != null) {
                return realUrl;
            }

        } catch (Exception e) {
            System.err.println("  ❌ 提取 URL 异常: " + e.getMessage());
            e.printStackTrace();
        }
        // 在 extractMediaUrl 的反射循环里，加这段打印所有字段
        System.out.println("  🔍 FileItem 所有字段:");
        for (Field field : mediaItem.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                System.out.println("    " + field.getName() + " = " + field.get(mediaItem));
            } catch (Exception e) {
                System.out.println("    " + field.getName() + " = [无法读取]");
            }
        }
        return null;
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