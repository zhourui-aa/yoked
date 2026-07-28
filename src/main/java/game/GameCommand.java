package game;

import org.example.bot.ilink.BotCluster;
import org.example.bot.ilink.ILinkBot;
import org.example.bot.service.AiService;

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
        // ⓪ 结束游戏 / 退出游戏
        // ═══════════════════════════════════════════
        if (GameRegistry.isRunning() && isQuitCommand(text)) {
            String gameName = GameRegistry.session().engine().name();
            GameRegistry.stop();
            bot.sendText(userId, "🚪 已退出「" + gameName + "」。");
            return true;
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
        if (text.equals("桌游模式") || text.equals("开启桌游")) {
            step = "menu";
            var sb = new StringBuilder();
            sb.append("🎮 桌游大厅\n\n可玩：").append(GameRegistry.listGames());
            sb.append("\n\n请输入「游戏名 人数」开始，例如：狼人杀 6");
            bot.sendText(userId, sb.toString());
            return true;
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
            return startGame(bot, ai, lobby);
        }

        return false;
    }

    // ═══════════════════════════════════════════
    // 游戏大厅消息处理（由 BotApp handler 调用）
    // ═══════════════════════════════════════════

    /** 人齐后自动开始（由 BotApp handler 检测到时调用） */
    public static void autoStart(ILinkBot bot, AiService ai, GameRegistry.GameLobby lobby) {
        startGame(bot, ai, lobby);
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

    /** 启动游戏 */
    private static boolean startGame(ILinkBot bot, AiService ai, GameRegistry.GameLobby lobby) {
        String[] names = lobby.toPlayerNames();
        GameRegistry.dismissLobby();
        GameRegistry.start(lobby.engine, ai, names);
        var session = GameRegistry.session();
        for (var e : lobby.boundMap().entrySet())
            session.bindUser(e.getValue(), e.getKey());

        String announce = lobby.engine.start(session);
        // 调 DeepSeek 执行角色分配
        String reply = session.prompt(announce);
        // 解析私信并分发
        for (String line : reply.split("\n")) {
            if (line.startsWith("【私信:") && line.contains("】")) {
                int end = line.indexOf("】");
                String who = line.substring(4, end);
                String msg = line.substring(end + 1).strip();
                String uid = session.getUserId(who);
                if (uid != null) {
                    bot.sendText(uid, "📨 " + msg);
                    System.out.println("[游戏:私信] → " + who + ": " + msg);
                }
            }
        }
        // 公开部分发给创建者
        bot.sendText(lobby.creatorId, "🎮 「" + lobby.engine.name() + "」开始！"
            + names.length + "位玩家。角色已私信告知。");
        step = null;
        gameName = null;
        System.out.println("[游戏] " + lobby.engine.name() + " 开始，" + names.length + "人");
        return true;
    }

    // ═══════════════════════════════════════════
    // 单人游戏快速启动
    // ═══════════════════════════════════════════

    /** 启动单人游戏（跳过 lobby 流程） */
    private static boolean startSinglePlayerGame(ILinkBot bot, AiService ai, String userId, GameEngine engine) {
        String playerName = "玩家";
        GameRegistry.start(engine, ai, new String[]{playerName});
        var session = GameRegistry.session();
        session.bindUser(userId, playerName);

        String announce = engine.start(session);
        String reply = session.prompt(announce);

        bot.sendText(userId, "🎮 「" + engine.name() + "」开始！\n\n" + reply);
        System.out.println("[游戏] " + engine.name() + " 单人游戏开始");
        return true;
    }

    // ═══════════════════════════════════════════
    // 游戏名匹配
    // ═══════════════════════════════════════════

    /**
     * 从自然语言中匹配游戏名（非精确前缀，用于"我想玩海龟汤"等场景）。
     * 匹配规则：
     * - 包含 "想玩" + 游戏名，如"我想玩海龟汤游戏"
     * - 包含 "玩" + 游戏名（非开头位置），如"我要玩海龟汤"
     * - 排除否定语境："不想玩"、"别玩"
     *
     * @return 匹配到的游戏名，未匹配返回 null
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
        return t.equals("结束游戏") || t.equals("退出游戏")
            || t.equals("不玩了") || t.equals("退出")
            || t.equals("结束") || t.equalsIgnoreCase("quit");
    }
}