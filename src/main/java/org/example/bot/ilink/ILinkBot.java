package org.example.bot.ilink;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.example.bot.model.BotMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 微信 iLink 机器人的简化门面（Facade）。
 *
 * <p>封装了 SDK 的 Builder 模式、监听器、LoginContext、contextToken，
 * 对外只暴露 7 个简单方法。
 *
 * <h3>用法</h3>
 * <ol>
 *   <li>{@link #create()} 创建机器人</li>
 *   <li>{@link #login()} 扫码登录</li>
 *   <li>{@link #pollMessages()} 拉取消息</li>
 *   <li>{@link #sendText(String, String)} / {@link #sendImage} 发送回复</li>
 *   <li>{@link #close()} 关闭连接</li>
 * </ol>
 */
public class ILinkBot {

    private final ILinkClient client;
    private LoginContext loginContext;

    private ILinkBot(ILinkClient client) {
        this.client = client;
    }

    /**
     * 创建一个 iLink 机器人实例。
     */
    public static ILinkBot create() {
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .writeTimeoutMs(35000)
                .httpMaxRetries(3)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000)
                .build();

        ILinkClient client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        System.out.println("[iLink] ✅ 登录成功！Bot ID: " + ctx.getBotId());
                    }
                    @Override
                    public void onLoginFailure(Throwable e) {
                        System.err.println("[iLink] ❌ 登录失败: " + e.getMessage());
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        System.out.println("[iLink] 📩 收到 " + messages.size() + " 条消息");
                    }
                })
                .build();

        return new ILinkBot(client);
    }

    /**
     * 开始登录流程 — 打印二维码，阻塞等待扫码完成。
     */
    public void login() {
        System.out.println("[iLink] 正在获取登录二维码...");
        String qrContent = client.executeLogin();
        System.out.println("请用微信扫描下方二维码登录：\n");
        System.out.println(qrContent);
        try {
            this.loginContext = client.getLoginFuture().get();
            System.out.println("[iLink] 登录流程完成！");
        } catch (Exception e) {
            throw new RuntimeException("登录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 拉取新消息 — 自动提取文字和图片内容。
     */
    public List<BotMessage> pollMessages() {
        List<WeixinMessage> rawMessages;
        try {
            rawMessages = client.getUpdates();
        } catch (IOException e) {
            System.err.println("[iLink] ⚠ 拉取消息失败: " + e.getMessage());
            return List.of();
        }

        List<BotMessage> result = new ArrayList<>();
        if (rawMessages != null) {
            for (WeixinMessage msg : rawMessages) {
                String userId = msg.getFrom_user_id();
                if (userId == null) continue;

                String text = extractText(msg);
                byte[] image = extractImage(msg);

                if (image != null) {
                    // 图片消息 — 带文字说明（如果有的话）
                    result.add(BotMessage.image(userId, image, text));
                } else if (!text.isEmpty()) {
                    // 纯文字消息
                    result.add(BotMessage.text(userId, text));
                }
            }
        }
        return result;
    }

    /** 发送文本消息。需要先收到过该用户的消息。 */
    public void sendText(String userId, String text) {
        try {
            client.sendText(userId, text);
        } catch (IOException e) {
            System.err.println("[iLink] ⚠ 发送消息失败: " + e.getMessage());
        }
    }

    /** 带"正在输入..."状态发送文本。 */
    public void sendTextWithTyping(String userId, String text, long typingMillis) {
        try {
            client.sendTextWithTyping(userId, text, typingMillis);
        } catch (IOException e) {
            System.err.println("[iLink] ⚠ 发送消息失败: " + e.getMessage());
        }
    }

    /** 发送图片。SDK 自动处理加密上传。 */
    public void sendImage(String userId, byte[] imageBytes, String fileName, String caption) {
        try {
            client.sendImage(userId, imageBytes, fileName,
                    caption != null ? caption : "");
        } catch (IOException e) {
            System.err.println("[iLink] ⚠ 发送图片失败: " + e.getMessage());
        }
    }

    public String getBotId() {
        return loginContext != null ? loginContext.getBotId() : null;
    }

    /** 关闭机器人，释放所有资源。 */
    public void close() {
        System.out.println("[iLink] 正在关闭...");
        client.close();
        System.out.println("[iLink] 已关闭。");
    }

    /** 从 SDK 原始消息中提取文字内容。type=1（文字）才会被提取。 */
    private static String extractText(WeixinMessage msg) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MessageItem item : items) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(item.getText_item().getText());
            }
        }
        return sb.toString();
    }

    /** 从 SDK 原始消息中提取图片内容。type=2（图片）才会下载。 */
    private byte[] extractImage(WeixinMessage msg) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return null;
        for (MessageItem item : items) {
            if (item.getImage_item() != null && item.getImage_item().getMedia() != null) {
                try {
                    return client.downloadImageFromMessageItem(item);
                } catch (IOException e) {
                    System.err.println("[iLink] ⚠ 下载图片失败: " + e.getMessage());
                }
            }
        }
        return null;
    }
}
