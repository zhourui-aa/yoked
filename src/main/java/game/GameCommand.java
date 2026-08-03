package game;

import org.example.bot.ilink.BotCluster;
import org.example.bot.ilink.ILinkBot;
import org.example.bot.service.AiService;
import game.impl.LifeSimEngine;

/**
 * 桌游命令处理。
 *
 * <p>流程：桌游模式 → 狼人杀 人数 → 代号 xxx → 加入 xxx（弹码）→ 扫码 → 反复加入 → 人齐自动开始
 */
public class GameCommand {

    // 当前状态：null=空闲, "menu"=已展示菜单, "codename"=等待输入代号, "lobby"=等待加入
    private static String step = null;
    private static String gameName = null;
    private static int totalSlots = 0;

    public static boolean handle(ILinkBot bot, AiService ai, String userId, String text,
                                   BotCluster cluster) {

        // ═══════════════════════════════════════════
        // ⓪ 结束游戏 / 退出桌游模式
        // ═══════════════════════════════════════════
        if (isQuitCommand(text)) {
            if (GameRegistry.isRunning()) {
                String gn = GameRegistry.session().engine().name();
                GameRegistry.stop();
                bot.sendText(userId, "🚪 已退出「" + gn + "」。");
                return true;
            }
            if (step != null) {
                resetState();
                bot.sendText(userId, "👋 已退出桌游大厅。");
                return true;
            }
        }

        // 游戏进行中时，拒绝启动新游戏
        if (GameRegistry.isRunning()) {
            bot.sendText(userId, "当前已有游戏进行中，请先输入「结束游戏」退出。");
            return true;
        }

        // ═══════════════════════════════════════════
        // ① "玩 游戏名" / "玩 游戏名 人数" → 精确前缀
        // ═══════════════════════════════════════════
        if (text.startsWith("玩")) {
            String rest = text.substring(1).strip();
            String[] parts = rest.split("\\s+");
            if (parts.length >= 1) {
                var engine = GameRegistry.get(parts[0]);
                if (engine != null) {
                    if (engine.minPlayers() == 1 && engine.maxPlayers() == 1) {
                        return startSinglePlayerGame(bot, ai, userId, engine);
                    }
                    if (parts.length >= 2) {
                        try {
                            int count = Integer.parseInt(parts[1]);
                            if (count >= engine.minPlayers() && count <= engine.maxPlayers()) {
                                gameName = parts[0];
                                totalSlots = count;
                                step = "codename";
                                bot.sendText(userId, "✅ 「" + gameName + "」" + count + "人局。\n"
                                    + "请输入你的代号（游戏内显示的名字），例如：代号 周瑞");
                                return true;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    step = "menu";
                    gameName = parts[0];
                    bot.sendText(userId, "🎮 「" + parts[0] + "」已选择。\n"
                        + "请输入人数（" + engine.minPlayers() + "-" + engine.maxPlayers() + "人），例如：" + parts[0] + " " + engine.minPlayers());
                    return true;
                }
            }
        }

        // ═══════════════════════════════════════════
        // ② 自然语言触发（含"想玩"等意图词）
        // ═══════════════════════════════════════════
        String matchedGame = matchGameName(text);
        if (matchedGame != null) {
            var engine = GameRegistry.get(matchedGame);
            if (engine != null) {
                if (engine.minPlayers() == 1 && engine.maxPlayers() == 1) {
                    return startSinglePlayerGame(bot, ai, userId, engine);
                }
                step = "menu";
                gameName = matchedGame;
                bot.sendText(userId, "🎮 「" + matchedGame + "」已选择。\n"
                    + "请输入人数（" + engine.minPlayers() + "-" + engine.maxPlayers() + "人），例如：" + matchedGame + " " + engine.minPlayers());
                return true;
            }
        }

        // ═══════════════════════════════════════════
        // ③ "桌游模式" → 展示菜单
        // ═══════════════════════════════════════════
        if (text.equals("桌游模式") || text.equals("开启桌游") || text.equals("桌游")) {
            step = "menu";
            var sb = new StringBuilder();
            sb.append("🎮 桌游大厅\n\n可玩：").append(GameRegistry.listGames());
            sb.append("\n\n单人游戏直接输入「游戏名」即可。\n多人游戏输入「游戏名 人数」，例如：狼人杀 6");
            bot.sendText(userId, sb.toString());
            return true;
        }

        // ═══════════════════════════════════════════
        // ③.⑤ Menu 模式下直接输入游戏名（不含人数）
        // ═══════════════════════════════════════════
        if ("menu".equals(step)) {
            var engine = GameRegistry.get(text);
            if (engine != null) {
                if (engine.minPlayers() == 1 && engine.maxPlayers() == 1) {
                    return startSinglePlayerGame(bot, ai, userId, engine);
                }
                gameName = text;
                bot.sendText(userId, "🎮 「" + text + "」已选择。\n"
                    + "请输入人数（" + engine.minPlayers() + "-" + engine.maxPlayers() + "人），例如：" + text + " " + engine.minPlayers());
                return true;
            }
        }

        // ═══════════════════════════════════════════
        // ④ "狼人杀 6" → 选游戏+人数，等待代号
        // ═══════════════════════════════════════════
        if ("menu".equals(step) || (GameRegistry.hasLobby() && GameRegistry.lobby().creatorId.equals(userId))) {
            // 解析 "游戏名 人数"
            String[] parts = text.split("\\s+");
            if (parts.length == 2) {
                var engine = GameRegistry.get(parts[0]);
                if (engine != null) {
                    int count;
                    try { count = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return false; }
                    if (count >= engine.minPlayers() && count <= engine.maxPlayers()) {
                        // 单人游戏直接开始
                        if (engine.minPlayers() == 1 && engine.maxPlayers() == 1) {
                            return startSinglePlayerGame(bot, ai, userId, engine);
                        }
                        gameName = parts[0];
                        totalSlots = count;
                        step = "codename";
                        bot.sendText(userId, "✅ 「" + gameName + "」" + count + "人局。\n"
                            + "请输入你的代号（游戏内显示的名字），例如：代号 周瑞");
                        return true;
                    }
                    bot.sendText(userId, gameName + " 需要 " + engine.minPlayers() + "-" + engine.maxPlayers() + " 人。");
                    return true;
                }
            }
        }

        // ═══════════════════════════════════════════
        // ⑤ "代号 周瑞"（或直接"周瑞"） → 创建者加入
        // ═══════════════════════════════════════════
        if ("codename".equals(step) && !text.startsWith("加入") && !text.startsWith("添加")) {
            String name = text;
            if (name.startsWith("代号")) {
                name = name.substring(2).strip();
            }
            name = name.strip();
            if (name.isBlank()) { bot.sendText(userId, "请输入你的代号，例如：周瑞"); return true; }
            if (name.length() > 8) { bot.sendText(userId, "代号最多8个字。"); return true; }

            var engine = GameRegistry.get(gameName);
            GameRegistry.createLobby(engine, totalSlots, userId);
            var lobby = GameRegistry.lobby();
            lobby.bindDirect(name, userId);
            step = "lobby";

            bot.sendText(userId, "🎮 「" + name + "」已加入「" + gameName + "」！"
                + "（1/" + totalSlots + "）\n"
                + "现在请输入「加入 昵称」让其他人扫码加入。");
            return true;
        }

        // ═══════════════════════════════════════════
        // ⑥ "加入 xxx"（或"加入xxx"） → 弹二维码
        // ═══════════════════════════════════════════
        if ("lobby".equals(step) && (text.startsWith("加入") || text.startsWith("添加"))) {
            var lobby = GameRegistry.lobby();
            if (lobby == null || !userId.equals(lobby.creatorId)) return false;

            String name = text;
            if (name.startsWith("加入")) name = name.substring(2).strip();
            else if (name.startsWith("添加")) name = name.substring(2).strip();
            name = name.strip();
            if (name.isBlank()) { bot.sendText(userId, "请输入昵称，例如：加入 张三 或 加入张三"); return true; }

            if (!lobby.reserve(name)) {
                if (lobby.totalJoined() >= lobby.slots)
                    bot.sendText(userId, "❌ 人数已满。输入「开始」启动游戏。");
                else
                    bot.sendText(userId, "❌「" + name + "」已被占用。");
                return true;
            }

            cluster.addBotDynamic(name);
            bot.sendText(userId, "📱 「" + name + "」的二维码已打印在终端，等待扫码..."
                + "（" + lobby.totalJoined() + "/" + lobby.slots + "）");
            return true;
        }

        // ═══════════════════════════════════════════
        // ⑦ "开始" → 人齐或手动启动
        // ═══════════════════════════════════════════
        if ("lobby".equals(step) && text.equals("开始")) {
            var lobby = GameRegistry.lobby();
            if (lobby == null) return false;
            if (!lobby.allBound()) {
                bot.sendText(userId, "还有 " + lobby.pendingCount() + " 人未扫码，请等待。");
                return true;
            }
            return startGame(bot, ai, lobby, cluster);
        }

        return false;
    }

    // ═══════════════════════════════════════════
    // 游戏大厅消息处理（由 BotApp handler 调用）
    // ═══════════════════════════════════════════

    /** 人齐后自动开始（由 BotApp handler 检测到时调用） */
    public static void autoStart(ILinkBot bot, AiService ai, GameRegistry.GameLobby lobby, BotCluster cluster) {
        startGame(bot, ai, lobby, cluster);
    }

    /** 检测到游戏 bot 扫码后回调，返回大厅状态消息 */
    public static String onBotBound(String botName) {
        var lobby = GameRegistry.lobby();
        if (lobby == null) return null;
        int n = lobby.totalJoined();
        if (n >= lobby.slots) {
            // 人齐，自动开始
            return "🎉 玩家" + n + "「" + botName + "」已准备！（" + n + "/" + lobby.slots + "）\n"
                + "人数已齐，游戏即将开始！";
        }
        return "✅ 玩家" + n + "「" + botName + "」已准备！（" + n + "/" + lobby.slots + "）";
    }

    /** 发送积攒的私信块 — 必须用玩家自己的 bot */
    private static void flushPrivate(String who, StringBuilder msg, ILinkBot fallback,
                                      GameSession session, BotCluster cluster, boolean addPrefix) {
        if (who == null || msg.isEmpty()) return;
        String text = msg.toString().strip();
        String uid = session.getUserId(who);
        if (uid == null) { msg.setLength(0); return; }
        ILinkBot playerBot = cluster.getBot(who);
        String prefix = addPrefix ? "📨 " : "";
        (playerBot != null ? playerBot : fallback).sendText(uid, prefix + text);
        System.out.println("[游戏:私信] → " + who + " (" + text.length() + "字符) bot="
            + (playerBot != null ? playerBot.name() : fallback.name()));
        msg.setLength(0);
    }

    /** 发送文本给玩家，自动选正确的 bot */
    private static void sendToPlayer(String who, String uid, String text, ILinkBot fallback,
                                      BotCluster cluster) {
        ILinkBot pb = cluster.getBot(who);
        if (pb != null) {
            pb.sendText(uid, text);
        } else {
            fallback.sendText(uid, text);
        }
        System.out.println("[游戏:发送] → " + who + " bot="
            + (pb != null ? pb.name() : fallback.name()));
    }

    /** 启动游戏 */
    private static boolean startGame(ILinkBot bot, AiService ai, GameRegistry.GameLobby lobby,
                                      BotCluster cluster) {
        String[] names = lobby.toPlayerNames();
        GameRegistry.dismissLobby();
        GameRegistry.start(lobby.engine, ai, names);
        var session = GameRegistry.session();
        for (var e : lobby.boundMap().entrySet())
            session.bindUser(e.getValue(), e.getKey());

        // 记录每个玩家用哪个 bot（用于后续跨 bot 发消息）
        for (var e : lobby.boundMap().entrySet()) {
            String nickname = e.getKey();
            ILinkBot pb = cluster.getBot(nickname);
            session.setPlayerBot(nickname, pb != null ? pb.name() : bot.name());
        }

        // ═══ 阶段 1：AI 生成案件背景 + 角色卡 ═══
        String announce = lobby.engine.start(session);
        String reply;
        try {
            reply = session.prompt(announce);
            System.out.println("[游戏] AI 回复长度: " + (reply != null ? reply.length() : 0) + " 字符");
        } catch (Exception e) {
            System.err.println("[游戏] ❌ AI 生成失败: " + e.getMessage());
            bot.sendText(lobby.creatorId, "❌ 剧本生成失败，请重试：" + e.getMessage());
            resetState();
            return false;
        }

        if (reply == null || reply.isBlank()) {
            bot.sendText(lobby.creatorId, "❌ AI 未返回剧本，请重新开始游戏。");
            resetState();
            return false;
        }

        // 解析 AI 回复：提取公开部分 + 私信块
        StringBuilder pubAnnounce = new StringBuilder();
        String privWho = null;
        StringBuilder privMsg = new StringBuilder();
        java.util.Set<String> receivedCards = new java.util.LinkedHashSet<>();
        int privCount = 0;

        for (String line : reply.split("\n")) {
            if (line.startsWith("【私信:") && line.contains("】")) {
                flushPrivate(privWho, privMsg, bot, session, cluster, false);
                int end = line.indexOf("】");
                privWho = cleanName(line.substring(4, end));
                receivedCards.add(privWho);
                String tail = line.substring(end + 1).strip();
                if (!tail.isEmpty()) privMsg.append(tail).append("\n");
                privCount++;
            } else if (privWho != null) {
                privMsg.append(line).append("\n");
            } else {
                pubAnnounce.append(line).append("\n");
            }
        }
        flushPrivate(privWho, privMsg, bot, session, cluster, false);
        System.out.println("[游戏] 解析完毕: " + privCount + " 个私信块, 已收到角色卡: " + receivedCards);

        // ═══ 阶段 2：补发遗漏的角色卡 ═══
        java.util.Set<String> allNames = new java.util.LinkedHashSet<>(java.util.Arrays.asList(names));
        allNames.removeAll(receivedCards);
        if (!allNames.isEmpty()) {
            System.out.println("[游戏] ⚠ 遗漏角色卡: " + allNames + "，补发中...");
            String pubSoFar = pubAnnounce.toString().strip();
            if (lobby.engine instanceof game.impl.MurderMysteryEngine mme) {
                String retryPrompt = mme.roleCardPrompt(
                    allNames.toArray(new String[0]), pubSoFar);
                try {
                    String retryReply = session.prompt(retryPrompt);
                    System.out.println("[游戏] 补发回复长度: " + (retryReply != null ? retryReply.length() : 0));
                    if (retryReply != null && !retryReply.isBlank()) {
                        privWho = null;
                        privMsg.setLength(0);
                        for (String line : retryReply.split("\n")) {
                            if (line.startsWith("【私信:") && line.contains("】")) {
                                flushPrivate(privWho, privMsg, bot, session, cluster, false);
                                int end = line.indexOf("】");
                                privWho = cleanName(line.substring(4, end));
                                receivedCards.add(privWho);
                                String tail = line.substring(end + 1).strip();
                                if (!tail.isEmpty()) privMsg.append(tail).append("\n");
                            } else if (privWho != null) {
                                privMsg.append(line).append("\n");
                            }
                            // 补发中的公开部分追加到公告
                        }
                        flushPrivate(privWho, privMsg, bot, session, cluster, false);
                    }
                } catch (Exception e) {
                    System.err.println("[游戏] ⚠ 补发失败: " + e.getMessage());
                }
            }
        }

        // ═══ 广播公开公告（案件背景）给所有玩家 ═══
        String pubStr = pubAnnounce.toString().strip();
        if (!pubStr.isEmpty()) {
            for (var e : lobby.boundMap().entrySet()) {
                sendToPlayer(e.getKey(), e.getValue(), pubStr, bot, cluster);
            }
            System.out.println("[游戏:广播] " + pubStr.substring(0, Math.min(80, pubStr.length())) + "...");
        }

        // ═══ 通知所有玩家游戏开始 ═══
        String startMsg = "🎮 「" + lobby.engine.name() + "」开始！" + names.length + "位玩家。\n"
            + "💬 你的发言会自动广播给所有玩家。说「我要搜证」获取线索（每人3次）。";
        for (var e : lobby.boundMap().entrySet()) {
            sendToPlayer(e.getKey(), e.getValue(), startMsg, bot, cluster);
        }

        resetState();
        System.out.println("[游戏] " + lobby.engine.name() + " 开始，" + names.length + "人，"
            + receivedCards.size() + "人已收到角色卡");
        return true;
    }

    private static void resetState() {
        step = null;
        gameName = null;
    }

    /** 容错：去掉 AI 误加的角色名如 "张三（警察）" → "张三" */
    private static String cleanName(String raw) {
        int paren = raw.indexOf('（');
        if (paren < 0) paren = raw.indexOf('(');
        return paren > 0 ? raw.substring(0, paren).strip() : raw.strip();
    }

    // ═══════════════════════════════════════════
    // 单人游戏快速启动
    // ═══════════════════════════════════════════

    /** 启动单人游戏（跳过 lobby 流程） */
    private static boolean startSinglePlayerGame(ILinkBot bot, AiService ai, String userId, GameEngine engine) {
        try {
        String playerName = "玩家";
        GameRegistry.start(engine, ai, new String[]{playerName});
        var session = GameRegistry.session();
        session.bindUser(userId, playerName);

        String announce = engine.start(session);

        if (engine instanceof LifeSimEngine sim) {
            bot.sendText(userId, "🎮 「" + engine.name() + "」开始！\n\n" + sim.welcomeMessage());
        } else {
            String reply = session.prompt(announce);
            bot.sendText(userId, "🎮 「" + engine.name() + "」开始！\n\n" + reply);
        }
        System.out.println("[游戏] " + engine.name() + " 单人游戏开始");
        } catch (Exception e) {
            System.err.println("[GameCommand] ❌ startSinglePlayerGame 异常: " + e.getMessage());
            e.printStackTrace();
            bot.sendText(userId, "游戏启动失败：" + e.getMessage());
            return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════
    // 游戏名匹配
    // ═══════════════════════════════════════════

    /**
     * 从自然语言中匹配游戏名（非精确前缀，用于"我想玩海龟汤"等场景）。
     */
    public static String matchGameName(String text) {
        if (text == null || text.isBlank()) return null;

        String stripped = text.replaceAll("\\s+", "");
        if (stripped.contains("不想玩") || stripped.contains("别玩")) {
            return null;
        }

        for (String name : GameRegistry.gameNames()) {
            if (stripped.contains("想玩" + name)) {
                return name;
            }
        }

        for (String name : GameRegistry.gameNames()) {
            if (stripped.contains("玩" + name)) {
                return name;
            }
        }

        return null;
    }

    /** 判断是否为退出游戏的指令 */
    public static boolean isQuitCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.equals("结束游戏") || t.equals("退出游戏") || t.equals("退出桌游模式")
            || t.equals("退出桌游") || t.equals("不玩了") || t.equals("退出")
            || t.equals("结束") || t.equals("返回") || t.equals("取消")
            || t.equals("再来一局") || t.equals("重开") || t.equals("重新开始")
            || t.equalsIgnoreCase("quit");
    }
}
