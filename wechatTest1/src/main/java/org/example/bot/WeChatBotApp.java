package org.example.bot;

/**
 * 微信 AI 聊天机器人 — 主程序入口。
 *
 * <h3>这个程序做什么？</h3>
 * <ol>
 *   <li>连接微信 iLink 平台，获取一个 Bot 身份</li>
 *   <li>用微信扫码登录</li>
 *   <li>进入消息循环：不断拉取用户发来的消息</li>
 *   <li>每条消息交给 DeepSeek AI 生成回复</li>
 *   <li>把 AI 回复发送回用户</li>
 * </ol>
 *
 * <h3>如何运行？</h3>
 * <pre>{@code
 * # 1. 设置 DeepSeek API Key（必需）
 * set DEEPSEEK_API_KEY=sk-你的密钥
 *
 * # 2. 编译并运行
 * mvn compile exec:java -Dexec.mainClass="org.example.bot.WeChatBotApp"
 * }</pre>
 *
 * <h3>如何换成其他 AI？</h3>
 * 只需要在 {@link #main} 方法中，把：
 * <pre>{@code new DeepSeekAiService("...")}</pre>
 * 替换为你自己的实现类（比如 {@code new OpenAiService("...")}），其他代码不用改。
 *
 * <h3>程序架构（从上到下）</h3>
 * <pre>
 * WeChatBotApp.main()      ← 你在这里（主程序入口，约 50 行）
 *     ├── ILinkBot         ← 封装微信 iLink SDK，提供简单方法
 *     └── AiService        ← AI 服务接口（DeepSeek/OpenAI/Claude 等实现）
 * </pre>
 *
 * <h3>消息处理流程</h3>
 * <pre>
 * 微信用户发消息 → ILinkBot 拉取 → WeChatBotApp 转发 → DeepSeek AI 生成回复
 *                                                              ↓
 * 微信用户收到回复 ← ILinkBot 发送 ← WeChatBotApp 收到回复 ←────┘
 * </pre>
 *
 * @see ILinkBot          微信 iLink 门面
 * @see AiService         AI 服务接口
 * @see DeepSeekAiService DeepSeek 实现
 */
public class WeChatBotApp {

    /** 系统提示词 — 定义 AI 的角色，可以按需修改 */
    private static final String SYSTEM_PROMPT = "你是一个友好的微信助手，请用简洁自然的中文回复。";

    /** 两次拉取消息之间的间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 2000;

    public static void main(String[] args) {
        // ================================================================
        //  第 1 步：创建并登录微信机器人
        // ================================================================

        System.out.println("========================================");
        System.out.println("  微信 AI 聊天机器人 启动中...");
        System.out.println("========================================");

        ILinkBot bot = ILinkBot.create();   // 用默认配置创建机器人
        bot.login();                         // 扫码登录（会阻塞等待）

        // ================================================================
        //  第 2 步：创建 AI 服务
        // ================================================================

        AiService ai = new DeepSeekAiService(SYSTEM_PROMPT);

        // ================================================================
        //  第 3 步：消息处理主循环
        // ================================================================

        System.out.println("\n[Bot] 🟢 开始监听消息...（按 Ctrl+C 退出）\n");

        try {
            while (true) {
                // --- 3a. 拉取新消息 ---
                java.util.List<BotMessage> messages = bot.pollMessages();

                // --- 3b. 逐条处理 ---
                for (BotMessage msg : messages) {
                    // 打印收到的消息
                    System.out.println("[收到] " + msg.userId() + " : " + msg.text());

                    // 调用 AI 生成回复
                    String reply = ai.chat(msg.text());

                    // 打印回复内容
                    System.out.println("[回复] " + reply);

                    // 带"正在输入..."状态发送回复（更好的用户体验）
                    bot.sendTextWithTyping(msg.userId(), reply, 1500L);
                }

                // --- 3c. 等待一段时间再拉下一次（避免过于频繁） ---
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            // Ctrl+C 会触发中断，正常退出
            System.out.println("\n[Bot] 收到退出信号...");
        } finally {
            // ================================================================
            //  第 4 步：清理资源
            // ================================================================
            bot.close();
            System.out.println("[Bot] 已安全退出。");
        }
    }
}
