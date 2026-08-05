package org.example.bot.ilink;

import org.example.bot.model.BotMessage;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Bot 集群 — 管理多个 ILinkBot 实例（每个对应一个微信号）。
 */
public class BotCluster {

    private final List<ILinkBot> bots = new CopyOnWriteArrayList<>();
    private volatile Consumer<BotMessage> handler;
    private final ExecutorService loginExecutor =
        Executors.newCachedThreadPool(r -> new Thread(r, "bot-login"));
    private CountDownLatch initialLatch = new CountDownLatch(0);

    private static final ThreadLocal<ILinkBot> CURRENT_BOT = new ThreadLocal<>();

    /** userId → 能向该用户发消息的 bot（该 bot 与该 userId 有过消息记录） */
    private final ConcurrentHashMap<String, ILinkBot> userBotMap = new ConcurrentHashMap<>();

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

    /** 初始化阶段批量添加 */
    public void addBot(String name) {
        addBotInternal(name, true, null);
    }

    /** 运行时动态添加 */
    public void addBotDynamic(String name) {
        addBotInternal(name, false, null);
    }

    /** 运行时动态添加，登录成功后执行回调 */
    public void addBotDynamic(String name, Runnable onLogin) {
        addBotInternal(name, false, onLogin);
    }

    private void addBotInternal(String name, boolean countDown, Runnable onLogin) {
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
                if (onLogin != null) onLogin.run();
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
            // 记录用户 → bot 映射（用于跨 bot 发消息）
            userBotMap.put(msg.userId(), bot);
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
        try { initialLatch.await(30, TimeUnit.MINUTES); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 按名称查找 bot */
    public ILinkBot getBot(String name) {
        return bots.stream().filter(b -> b.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * 向指定用户发消息：优先用 userBotMap 记录的 bot，其次当前线程 bot，最后取第一个在线 bot。
     * 禁止无差别群发——每个 bot 的 context_token 是独立的，乱发会导致 SDK 报错。
     */
    public void sendToUser(String userId, String text) {
        ILinkBot bot = userBotMap.get(userId);
        if (bot == null) {
            bot = CURRENT_BOT.get();
            if (bot != null) userBotMap.put(userId, bot); // 记住映射，下次直接用
        }
        if (bot == null && !bots.isEmpty()) bot = bots.get(0);
        if (bot != null) bot.sendText(userId, text);
        else System.err.println("[BotCluster] ❌ 无可用 bot 发送消息给 " + userId);
    }

    /** 按名称移除并关闭一个 bot */
    public void removeBot(String name) {
        for (ILinkBot b : bots) {
            if (b.name().equals(name)) {
                bots.remove(b);
                b.close();
                // 清理 userBotMap 中该 bot 的映射
                userBotMap.entrySet().removeIf(e -> e.getValue() == b);
                System.out.println("[BotCluster] " + name + " 已移除");
                return;
            }
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