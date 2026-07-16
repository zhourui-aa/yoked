package com.github.wechat.ilink.sdk;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

public class ILinkDemo {
    public static void main(String[] args) throws Exception {
        // 1. 配置
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .heartbeatEnabled(true)
                .build();

        // 2. 创建客户端
        ILinkClient client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        System.out.println("✅ 登录成功，botId = " + context.getBotId());
                    }
                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("❌ 登录失败: " + throwable.getMessage());
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            System.out.println("📨 收到消息 from: " + msg.getFrom_user_id());
                            if (msg.getItem_list() != null) {
                                for (MessageItem item : msg.getItem_list()) {
                                    if (item.getText_item() != null) {
                                        String text = item.getText_item().getText();
                                        System.out.println("💬 内容: " + text);
                                    }
                                }
                            }
                        }
                    }
                })
                .build();

        // 3. 扫码登录
        String qrCode = client.executeLogin();
        System.out.println("请扫码登录：\n" + qrCode);

        // 4. 等待登录完成
        LoginContext context = client.getLoginFuture().get();
        System.out.println("登录完成！");

        // 5. 发送文本消息（需要替换为真实用户ID）
        String targetUserId = "这里替换成真实的 from_user_id";
        client.sendText(targetUserId, "Hello, iLink!");

        // 6. 关闭
        // client.close();
    }
}