package org.example.bot.ilink;

import org.example.bot.model.BotMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bot 集群 — 管理多个 ILinkBot 实例（每个对应一个微信号），
 * 支持启动时批量添加 + 运行时通过命令动态新增。
 *
 * <h3>使用方式</h3>
 * <pre>
 *   BotCluster cluster = new BotCluster();
 *   cluster.addBot("客服号");
 *   cluster.setHandler(msg -> { ... });
 *   cluster.awaitLogins();
 *   // 运行时可通过命令: cluster.addBot("新号");
 * </pre>
 */
public class BotCluster {

    private final List<ILinkBot> bots = new CopyOnWriteArrayList<>();
    private volatile Consumer<BotMessage> handler;
    private final ExecutorService loginExecutor =
        Executors.newCachedThreadPool(r -> new Thread(r, "bot-login"));
    private CountDownLatch initialLatch = new CountDownLatch(0);

    private static final ThreadLocal<ILinkBot> CURRENT_BOT = new ThreadLocal<>();

    /** 获取当前处理消息的 bot */
    public static ILinkBot current() {
        return CURRENT_BOT.get();
    }

    /** 注册消息处理器 */
    public void setHandler(Consumer<BotMessage> handler) {
        this.handler = handler;
        for (ILinkBot bot : bots) {
            wrapHandler(bot);
        }
    }

    /** 初始化阶段批量添加（会追踪 CountDownLatch） */
    public void addBot(String name) {
        addBotInternal(name, true);
    }

    /** 运行时动态添加（不参与 CountDownLatch） */
    public void addBotDynamic(String name) {
        addBotInternal(name, false);
    }

    private void addBotInternal(String name, boolean countDown) {
        ILinkBot bot = ILinkBot.create(name);
        bots.add(bot);
        if (handler != null) {
            wrapHandler(bot);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  🤖 Bot [" + name + "] — 请用微信扫码登录");
        System.out.println("=".repeat(60));

        // 如果初始化阶段，扩大 CountDownLatch
        if (countDown) {
            CountDownLatch old = initialLatch;
            initialLatch = new CountDownLatch((int) old.getCount() + 1);
        }

        loginExecutor.submit(() -> {
            // 短暂间隔避免多个二维码同时打印重叠
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            try {
                bot.login();
                bot.startPolling();
                System.out.println("[BotCluster] " + name + " 🟢 已上线");
            } catch (Exception e) {
                System.err.println("[BotCluster] " + name + " 启动失败: " + e.getMessage());
            } finally {
                if (countDown) initialLatch.countDown();
            }
        });

        // 小延迟让二维码完整输出
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
    }

    private void wrapHandler(ILinkBot bot) {
        final Consumer<BotMessage> h = this.handler;
        bot.setHandler(msg -> {
            CURRENT_BOT.set(bot);
            try {
                h.accept(msg);
            } finally {
                CURRENT_BOT.remove();
            }
        });
    }

    public int size() {
        return bots.size();
    }

    /** 阻塞等待初始化阶段添加的所有 bot 登录完成 */
    public void awaitLogins() {
        try {
            initialLatch.await(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 关闭所有 bot */
    public void closeAll() {
        System.out.println("[BotCluster] 正在关闭所有 bot...");
        loginExecutor.shutdownNow();
        for (ILinkBot bot : bots) {
            bot.close();
        }
        System.out.println("[BotCluster] 全部已关闭。");
    }
}
