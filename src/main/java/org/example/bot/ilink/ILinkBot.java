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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 微信 iLink 机器人的简化门面。
 *接受和发送消息，翻译成bot能看懂和我们能看懂的形式
 * <p>消息接收基于 SDK 长轮询 {@code getUpdates()}，Listener 仅用于日志。
 */
public class ILinkBot {

    private final String name;
    private final ILinkClient client;
    private LoginContext loginContext;
    private Consumer<BotMessage> handler;
    private final ExecutorService handlerExecutor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "msg-handler"));

    private ILinkBot(String name, ILinkClient client) {
        this.name = (name != null && !name.isBlank()) ? name : "default";
        this.client = client;
    }

    public String name() { return name; }

    /** 注册消息处理器 — 每条消息到达时调用（在 handler 线程中执行） */
    public void setHandler(Consumer<BotMessage> handler) {
        this.handler = handler;
    }

    /** 创建默认命名的 bot */
    public static ILinkBot create() {
        return create("default");
    }

    /** 创建指定名称的 bot（用于多 bot 场景区分日志） */
    public static ILinkBot create(String name) {
        String tag = name != null && !name.isBlank() ? name : "default";
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000).readTimeoutMs(35000).writeTimeoutMs(35000)
                .httpMaxRetries(3).heartbeatEnabled(true).heartbeatIntervalMs(30000)
                .build();

        ILinkClient client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override public void onLoginSuccess(LoginContext ctx) {
                        System.out.println("[iLink:" + tag + "] ✅ 登录成功！Bot ID: " + ctx.getBotId());
                    }
                    @Override public void onLoginFailure(Throwable e) {
                        System.err.println("[iLink:" + tag + "] ❌ 登录失败: " + e.getMessage());
                    }
                })
                .onMessage(msgs -> System.out.println("[iLink:" + tag + "] 📩 Listener 通知 " + msgs.size() + " 条"))
                .build();

        return new ILinkBot(tag, client);
    }

    public void login() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            System.out.println("[iLink:" + name + "] 正在获取登录二维码...（第 " + attempt + " 次）");
            String qrContent = client.executeLogin();
            System.out.println("【" + name + "】请用微信扫描下方二维码登录：\n");
            System.out.println(qrContent);
            try {
                this.loginContext = client.getLoginFuture().get();
                System.out.println("[iLink:" + name + "] 登录流程完成！");
                return;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = (cause.getMessage() != null ? cause.getMessage() : "")
                    + (e.getMessage() != null ? e.getMessage() : "");
                if (msg.contains("expired") || msg.contains("过期") || msg.contains("SessionExpired")) {
                    System.out.println("[iLink:" + name + "] ⚠ 二维码已过期，重新生成...");
                } else {
                    throw new RuntimeException("登录失败: " + msg, e);
                }
            }
        }
        throw new RuntimeException("登录失败：二维码过期次数超过 10 次");
    }

    /**
     * 启动长轮询线程 — SDK 核心消息接收机制。
     * {@code getUpdates()} 阻塞等待消息（最长 35s 超时），收到后交 handler 处理。
     */
    public void startPolling() {
        Thread poller = new Thread(() -> {
            System.out.println("[iLink:" + name + "] 🔄 长轮询已启动");
            while (!Thread.interrupted()) {
                try {
                    List<WeixinMessage> msgs = client.getUpdates();
                    if (msgs != null && !msgs.isEmpty()) {
                        System.out.println("[iLink:" + name + "] 📩 收到 " + msgs.size() + " 条消息");
                        for (WeixinMessage wm : msgs) {
                            BotMessage bm = toBotMessage(wm);
                            if (bm != null && handler != null) {
                                handlerExecutor.submit(() -> handler.accept(bm));
                            }
                        }
                    }
                } catch (IOException e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    System.err.println("[iLink:" + name + "] ⚠ getUpdates 异常: " + msg);
                    if (msg.contains("expired") || msg.contains("过期") || msg.contains("SessionExpired")) {
                        System.out.println("[iLink:" + name + "] 🔐 会话过期，尝试重新登录...");
                        if (tryRelogin()) {
                            System.out.println("[iLink:" + name + "] ✅ 重新登录成功，继续轮询");
                            continue;
                        } else {
                            System.err.println("[iLink:" + name + "] ❌ 重新登录失败，5秒后重试...");
                        }
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    System.err.println("[iLink:" + name + "] ⚠ 轮询异常: " + msg);
                    if (msg.contains("expired") || msg.contains("过期") || msg.contains("SessionExpired")) {
                        System.out.println("[iLink:" + name + "] 🔐 会话过期，尝试重新登录...");
                        if (tryRelogin()) {
                            System.out.println("[iLink:" + name + "] ✅ 重新登录成功，继续轮询");
                            continue;
                        }
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { break; }
                }
            }
            System.out.println("[iLink:" + name + "] 🔄 长轮询已停止");
        }, "ilink-poller");
        poller.setDaemon(true);
        poller.start();
    }

    /** 尝试重新登录，成功返回 true */
    private boolean tryRelogin() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            System.out.println("[iLink:" + name + "] 重新登录...（第 " + attempt + " 次）");
            String qrContent = client.executeLogin();
            System.out.println(qrContent);
            try {
                this.loginContext = client.getLoginFuture().get();
                System.out.println("[iLink:" + name + "] ✅ 重新登录成功！Bot ID: " + loginContext.getBotId());
                return true;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = (cause.getMessage() != null ? cause.getMessage() : "")
                    + (e.getMessage() != null ? e.getMessage() : "");
                if (msg.contains("expired") || msg.contains("过期")) {
                    System.out.println("[iLink:" + name + "] ⚠ 二维码过期，重试...");
                } else {
                    System.err.println("[iLink:" + name + "] ❌ 重新登录失败: " + msg);
                    return false;
                }
            }
        }
        return false;
    }

    // ---- 发送 ----
    public void sendText(String userId, String text) {
        try { client.sendText(userId, text); }
        catch (IOException e) { System.err.println("[iLink:" + name + "] ⚠ 发送失败: " + e.getMessage()); }
    }

    public void sendTextWithTyping(String userId, String text, long typingMillis) {
        try { client.sendTextWithTyping(userId, text, typingMillis); }
        catch (IOException e) { System.err.println("[iLink:" + name + "] ⚠ 发送失败: " + e.getMessage()); }
    }

    public void sendImage(String userId, byte[] imageBytes, String fileName, String caption) {
        try { client.sendImage(userId, imageBytes, fileName, caption != null ? caption : ""); }
        catch (IOException e) { System.err.println("[iLink:" + name + "] ⚠ 发送失败: " + e.getMessage()); }
    }

    public void sendFile(String userId, byte[] fileBytes, String fileName, String caption) {
        try { client.sendFile(userId, fileBytes, fileName, caption != null ? caption : ""); }
        catch (IOException e) { System.err.println("[iLink:" + name + "] ⚠ 发送失败: " + e.getMessage()); }
    }

    public void sendVoiceWithText(String userId, byte[] audioBytes, String fileName, String text) {
        try { client.sendFile(userId, audioBytes, fileName, ""); }
        catch (IOException e) { System.err.println("[iLink:" + name + "] ⚠ 发送失败: " + e.getMessage()); }
        if (text != null && !text.isBlank()) sendText(userId, "🎤 " + text);
    }

    public String getBotId() { return loginContext != null ? loginContext.getBotId() : null; }

    public void close() {
        System.out.println("[iLink:" + name + "] 正在关闭...");
        handlerExecutor.shutdown();
        client.close();
        System.out.println("[iLink:" + name + "] 已关闭。");
    }

    // ---- 消息转换 ----
    private BotMessage toBotMessage(WeixinMessage wm) {
        String userId = wm.getFrom_user_id();
        if (userId == null) return null;

        List<MessageItem> items = wm.getItem_list();
        if (items == null || items.isEmpty()) return null;

        for (MessageItem item : items) {
            if (item.getVoice_item() != null) {
                String text = item.getVoice_item().getText();
                byte[] audio = null;
                if (item.getVoice_item().getMedia() != null) {
                    try { audio = client.downloadVoiceFromMessageItem(item); }
                    catch (IOException e) { System.err.println("[iLink:" + name + "] ⚠ 下载语音失败: " + e.getMessage()); }
                }
                return BotMessage.voice(userId, audio != null ? audio : new byte[0], text);
            }
            if (item.getImage_item() != null && item.getImage_item().getMedia() != null) {
                byte[] image = null;
                try { image = client.downloadImageFromMessageItem(item); }
                catch (IOException e) { System.err.println("[iLink:" + name + "] ⚠ 下载图片失败: " + e.getMessage()); }
                if (image != null) {
                    String caption = item.getText_item() != null ? item.getText_item().getText() : "";
                    return BotMessage.image(userId, image, caption != null ? caption : "");
                }
            }
            if (item.getFile_item() != null && item.getFile_item().getMedia() != null) {
                try {
                    byte[] data = client.downloadFileFromMessageItem(item);
                    String name = item.getFile_item().getFile_name();
                    return BotMessage.file(userId, data, name != null ? name : "file");
                } catch (IOException e) { System.err.println("[iLink:" + name + "] ⚠ 下载文件失败: " + e.getMessage()); }
            }
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                return BotMessage.text(userId, item.getText_item().getText());
            }
        }
        return null;
    }
}
