package org.example.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 微信 iLink 机器人的简化门面（Facade）。
 *
 * <h3>为什么需要这个类？</h3>
 * wechat-ilink-sdk 功能很强大，但直接使用需要理解 Builder 模式、监听器、
 * LoginContext、contextToken 等概念，对初级开发者不够友好。
 *
 * <p>这个类把 SDK 的复杂性封装在内部，对外只暴露 6 个简单方法。
 * 你只需要：
 * <ol>
 *   <li>{@link #create()} 创建机器人</li>
 *   <li>{@link #login()} 扫码登录</li>
 *   <li>{@link #pollMessages()} 拉取消息</li>
 *   <li>{@link #sendText(String, String)} 发送回复</li>
 *   <li>{@link #close()} 关闭连接</li>
 * </ol>
 *
 * <h3>你需要知道的几个重要概念（SDK 已自动处理，无需手动操作）</h3>
 * <ul>
 *   <li><b>contextToken</b>：发送消息时需要的上下文令牌。SDK 在拉取消息时
 *       自动提取并缓存，发送消息时自动传入。你不需要关心它。</li>
 *   <li><b>cursor</b>：分页拉取消息的游标。SDK 自动管理，防止重复拉取。</li>
 *   <li><b>心跳</b>：SDK 默认每 30 秒自动拉取一次消息保持连接。</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>必须先收到用户消息，才能向该用户发送回复（需要 contextToken）</li>
 *   <li>{@link #login()} 会阻塞等待扫码，确保你的终端能显示二维码</li>
 *   <li>用完后记得调用 {@link #close()} 释放资源</li>
 * </ul>
 *
 * @see WeChatBotApp 主程序示例
 */
public class ILinkBot {

    // ---- 内部状态 ----

    /** SDK 客户端，所有操作都通过它完成 */
    private final ILinkClient client;

    /** 登录成功后的上下文，包含 botId、userId、botToken 等 */
    private LoginContext loginContext;

    // ---- 构造器（私有，通过 create() 创建） ----

    /**
     * 私有构造器。请使用 {@link #create()} 创建实例。
     */
    private ILinkBot(ILinkClient client) {
        this.client = client;
    }

    // ========================================================================
    //  公开方法 — 你只需要关心下面这些
    // ========================================================================

    /**
     * 创建一个 iLink 机器人实例，使用推荐的默认配置。
     *
     * <p>默认配置：
     * <ul>
     *   <li>连接/读/写超时：各 35 秒</li>
     *   <li>HTTP 重试次数：3 次</li>
     *   <li>心跳：开启，每 30 秒一次</li>
     * </ul>
     *
     * <p>登录成功/失败时会自动打印日志，收到消息时也会打印数量。
     *
     * @return 可用的 ILinkBot 实例
     */
    public static ILinkBot create() {
        // 创建配置 — 使用合理的默认值
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)   // 连接超时 35 秒
                .readTimeoutMs(35000)      // 读取超时 35 秒
                .writeTimeoutMs(35000)     // 写入超时 35 秒
                .httpMaxRetries(3)         // 失败重试 3 次
                .heartbeatEnabled(true)    // 开启心跳
                .heartbeatIntervalMs(30000) // 心跳间隔 30 秒
                .build();

        // 构建客户端，设置监听器
        ILinkClient client = ILinkClient.builder()
                .config(config)
                // --- 登录监听器：登录成功/失败时触发 ---
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        System.out.println("[iLink] ✅ 登录成功！");
                        System.out.println("[iLink]    Bot ID  : " + ctx.getBotId());
                        System.out.println("[iLink]    User ID : " + ctx.getUserId());
                    }

                    @Override
                    public void onLoginFailure(Throwable e) {
                        System.err.println("[iLink] ❌ 登录失败: " + e.getMessage());
                    }
                })
                // --- 消息监听器：收到新消息时触发（补充通知，实际消息由 pollMessages() 返回）---
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
     * 开始登录流程。
     *
     * <p>调用后：
     * <ol>
     *   <li>控制台会打印一张二维码</li>
     *   <li>用你的微信扫描这个二维码</li>
     *   <li>扫描成功后自动完成登录，方法返回</li>
     * </ol>
     *
     * <p><b>注意：这是一个阻塞方法</b>，会一直等到扫码成功或超时才返回。
     *
     * @throws RuntimeException 如果登录失败（二维码过期、网络错误等）
     */
    public void login() {
        System.out.println("[iLink] 正在获取登录二维码...");

        // executeLogin() 返回二维码的文本内容，同时启动后台轮询等待扫码
        String qrContent = client.executeLogin();
        System.out.println("请用微信扫描下方二维码登录：\n");
        System.out.println(qrContent);

        // getLoginFuture() 返回一个 CompletableFuture，.get() 阻塞等待直到登录完成
        try {
            CompletableFuture<LoginContext> future = client.getLoginFuture();
            this.loginContext = future.get();  // 阻塞等待
            System.out.println("[iLink] 登录流程完成！");
        } catch (Exception e) {
            throw new RuntimeException("登录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 拉取新消息。
     *
     * <p>这个方法会：
     * <ol>
     *   <li>向微信服务器请求最新的消息</li>
     *   <li>从每条消息中提取文字内容</li>
     *   <li>过滤掉不含文字的消息（纯图片、文件等）</li>
     *   <li>返回干净的 {@link BotMessage} 列表</li>
     * </ol>
     *
     * <p><b>重要：</b>每拉取一次，SDK 会自动缓存 contextToken，
     * 之后你就可以正常用 {@link #sendText} 向这些用户发送回复了。
     *
     * @return 新消息列表（可能为空列表，表示没有新消息）
     */
    public List<BotMessage> pollMessages() {
        List<WeixinMessage> rawMessages;
        try {
            rawMessages = client.getUpdates();
        } catch (IOException e) {
            System.err.println("[iLink] ⚠ 拉取消息失败: " + e.getMessage());
            return List.of();  // 返回空列表，让主循环可以继续
        }

        // 把原始 SDK 消息转换成我们自己的 BotMessage
        List<BotMessage> result = new ArrayList<>();
        if (rawMessages != null) {
            for (WeixinMessage msg : rawMessages) {
                String userId = msg.getFrom_user_id();
                if (userId == null) continue;  // 跳过无发送者的消息

                String text = extractText(msg);
                if (text.isEmpty()) continue;  // 跳过不含文字的消息

                result.add(new BotMessage(userId, text));
            }
        }

        return result;
    }

    /**
     * 向指定用户发送文本消息。
     *
     * <p><b>前提条件：</b>你必须先收到过该用户发来的消息，
     * 否则 SDK 中没有该用户的 contextToken，发送会失败。
     *
     * @param userId 接收者的微信用户 ID（从 {@link BotMessage#userId()} 获取）
     * @param text   要发送的文本内容
     */
    public void sendText(String userId, String text) {
        try {
            client.sendText(userId, text);
        } catch (IOException e) {
            System.err.println("[iLink] ⚠ 发送消息失败: " + e.getMessage());
        }
    }

    /**
     * 向指定用户发送文本消息，发送前先显示"正在输入..."状态。
     *
     * <p>用这个方法比直接用 {@link #sendText} 用户体验更好，
     * 因为对方能看到 bot 正在打字，知道回复马上就到。
     *
     * <p>流程：
     * <ol>
     *   <li>显示"正在输入..."状态</li>
     *   <li>等待 typingMillis 毫秒（模拟打字时间）</li>
     *   <li>发送消息</li>
     *   <li>取消"正在输入..."状态</li>
     * </ol>
     *
     * @param userId       接收者的微信用户 ID
     * @param text         要发送的文本内容
     * @param typingMillis 显示"正在输入"的毫秒数（建议 1000~2000）
     */
    public void sendTextWithTyping(String userId, String text, long typingMillis) {
        try {
            client.sendTextWithTyping(userId, text, typingMillis);
        } catch (IOException e) {
            System.err.println("[iLink] ⚠ 发送消息失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前登录的 Bot ID。
     *
     * @return Bot ID 字符串，如果尚未登录则返回 {@code null}
     */
    public String getBotId() {
        return loginContext != null ? loginContext.getBotId() : null;
    }

    /**
     * 关闭机器人，释放所有资源（网络连接、线程池等）。
     *
     * <p><b>重要：</b>程序退出前务必调用此方法，否则可能资源泄漏。
     */
    public void close() {
        System.out.println("[iLink] 正在关闭...");
        client.close();
        System.out.println("[iLink] 已关闭。");
    }

    // ========================================================================
    //  内部辅助方法
    // ========================================================================

    /**
     * 从 SDK 的原始消息对象中提取文字内容。
     *
     * <p>一条微信消息可能包含多个 item（比如文字+图片），
     * 我们只提取其中类型为 1（文字）的部分，把它们拼接起来。
     *
     * <p>消息 item 类型说明：
     * <ul>
     *   <li>1 — 文字（TextItem）</li>
     *   <li>2 — 图片（ImageItem）</li>
     *   <li>3 — 语音（VoiceItem）</li>
     *   <li>4 — 文件（FileItem）</li>
     *   <li>5 — 视频（VideoItem）</li>
     * </ul>
     *
     * @param msg SDK 原始消息对象
     * @return 提取到的文字内容，不含文字时返回空字符串
     */
    private static String extractText(WeixinMessage msg) {
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (MessageItem item : items) {
            // 只处理文字类型（type == 1）
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                if (sb.length() > 0) sb.append("\n");  // 多条文字用换行分隔
                sb.append(item.getText_item().getText());
            }
        }
        return sb.toString();
    }
}
