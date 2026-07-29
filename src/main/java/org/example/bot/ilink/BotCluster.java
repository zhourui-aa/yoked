package org.example.bot.ilink;

import org.example.bot.model.BotMessage;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Bot 集群 — 管理多个 ILinkBot 实例（每个对应一个微信号），
 * 支持启动时批量添加 + 运行时通过命令动态新增。
 */
public class BotCluster {

    private final List<ILinkBot> bots = new CopyOnWriteArrayList<>();
    private volatile Consumer<BotMessage> handler;
    private final ExecutorService loginExecutor =
        Executors.newCachedThreadPool(r -> new Thread(r, "bot-login"));
    private CountDownLatch initialLatch = new CountDownLatch(0);

    private static final ThreadLocal<ILinkBot> CURRENT_BOT = new ThreadLocal<>();

    /** userId → 能成功给该用户发消息的 bot */
    private final ConcurrentHashMap<String, ILinkBot> userBotMap = new ConcurrentHashMap<>();

    /** 获取当前处理消息的 bot */
    public static ILinkBot current() {
        return CURRENT_BOT.get();
    }

    /**
     * 向所有指定用户发送消息 — 每个 bot 只发给它自己的用户。
     * 这是最可靠的跨 bot 发送方式。
     */
    public void broadcastToUsers(java.util.Collection<String> userIds, String text) {
        java.util.Map<ILinkBot, java.util.List<String>> byBot = new java.util.HashMap<>();
        for (String uid : userIds) {
            ILinkBot bot = userBotMap.get(uid);
            if (bot != null) {
                byBot.computeIfAbsent(bot, k -> new java.util.ArrayList<>()).add(uid);
            }
        }
        for (java.util.Map.Entry<ILinkBot, java.util.List<String>> entry : byBot.entrySet()) {
            ILinkBot bot = entry.getKey();
            for (String uid : entry.getValue()) {
                bot.sendText(uid, text);
            }
        }
        // 兜底：未映射的用户用所有 bot 发
        for (String uid : userIds) {
            if (userBotMap.containsKey(uid)) continue;
            for (ILinkBot bot : bots) {
                bot.sendText(uid, text);
            }
        }
    }

    /** 向单个用户发送消息（使用其对应的 bot） */
    public void sendToUser(String userId, String text) {
        ILinkBot bot = userBotMap.get(userId);
        if (bot != null) {
            bot.sendText(userId, text);
            return;
        }
        // 兜底
        for (ILinkBot b : bots) {
            b.sendText(userId, text);
        }
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

        if (countDown) {
            CountDownLatch old = initialLatch;
            initialLatch = new CountDownLatch((int) old.getCount() + 1);
        }

        loginExecutor.submit(() -> {
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

        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
    }

    private void wrapHandler(ILinkBot bot) {
        final Consumer<BotMessage> h = this.handler;
        bot.setHandler(msg -> {
            CURRENT_BOT.set(bot);
            // 记录该消息来自的用户 → 这个 bot
            userBotMap.put(msg.userId(), bot);
            try {
                h.accept(msg);
            } finally {
                CURRENT_BOT.remove();
            }
        });
    }

    public int size() { return bots.size(); }

    public void awaitLogins() {
        try { initialLatch.await(30, TimeUnit.MINUTES); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void closeAll() {
        System.out.println("[BotCluster] 正在关闭所有 bot...");
        loginExecutor.shutdownNow();
        for (ILinkBot bot : bots) {
            bot.close();
        }
        System.out.println("[BotCluster] 全部已关闭。");
    }
}
