package game;

import org.example.bot.ilink.BotCluster;
import org.example.bot.ilink.ILinkBot;
import org.example.bot.service.AiService;

/**
 * 桌游命令处理 — 从 BotApp 的 tryHandleLocalCommand 中调用。
 */
public class GameCommand {

    /** 处理桌游相关命令，匹配则返回 true */
    public static boolean handle(ILinkBot bot, AiService ai, String userId, String text, BotCluster cluster) {

        // ⭐ 自然语言匹配：把"我想玩狼人杀"变成"玩 狼人杀 我"
        // 跳过已以"玩 "开头的命令，防止无限递归
        if (!text.startsWith("玩 ")) {
            String matched = smartMatch(text);
            if (matched != null) {
                return handle(bot, ai, userId, matched, cluster);
            }
        }
        // "开启桌游" / "桌游列表"
        if (text.equals("开启桌游") || text.equals("桌游模式") || text.equals("桌游列表")) {
            bot.sendText(userId, "🎮 可玩的桌游：" + GameRegistry.listGames()
                + "\n\n输入「玩 游戏名 你的昵称 玩家2 玩家3 ...」开始游戏。\n单人游戏只需：玩 游戏名 你的昵称");
            return true;
        }

        // "加入" — 生成邀请二维码
        if (text.equals("加入") && GameRegistry.gameState() == GameRegistry.GameState.WAITING) {
            GameSession gs = GameRegistry.session();
            if (gs == null) {
                bot.sendText(userId, "当前没有游戏。");
                return true;
            }
            // 只有已绑定的玩家（房主）才能生成二维码
            if (gs.playerName(userId) == null) {
                bot.sendText(userId, "只有房主才能生成邀请二维码。");
                return true;
            }
            String tag = "game-" + gs.engine().name() + "-" + System.currentTimeMillis();
            cluster.addBotDynamic(tag);
            bot.sendText(userId, "📱 已生成邀请二维码，请截图发给朋友扫码。\n扫码后自动加入游戏。");
            return true;
        }

// "加入 xxx" — 手动绑定昵称
        if (text.startsWith("加入 ") && GameRegistry.isRunning()
                && GameRegistry.gameState() == GameRegistry.GameState.WAITING) {
            GameSession gs = GameRegistry.session();
            if (gs == null) {
                bot.sendText(userId, "当前没有游戏。");
                return true;
            }
            String name = text.substring(3).strip();
            if (name.isBlank()) {
                bot.sendText(userId, "请输入你的昵称，例如：加入 张三");
                return true;
            }
            gs.bindUser(userId, name);
            bot.sendText(userId, "✅ " + name + " 已加入「" + gs.engine().name() + "」！");
            String ready = GameRegistry.checkReady();
            if (ready == null) {
                bot.sendText(userId, "当前没有游戏。");
            } else if ("ready".equals(ready)) {
                bot.sendText(userId, "🎯 人数已够，发送「开始游戏」开始！");
            } else {
                bot.sendText(userId, "⏳ " + ready);
            }
            return true;
        }

        // "玩 游戏名 玩家列表"
        if (text.startsWith("玩 ")) {
            String rest = text.substring(2).strip();
            String[] parts = rest.split("\\s+");
            if (parts.length < 1) {
                bot.sendText(userId, "请指定游戏名。\n例如：玩 谁是卧底");
                return true;
            }
            String gameName = parts[0];
            GameEngine engine = GameRegistry.get(gameName);
            if (engine == null) {
                bot.sendText(userId, "没有「" + gameName + "」这个游戏。\n" + GameRegistry.listGames());
                return true;
            }

            // 创建空房间（0个玩家）
            GameRegistry.start(engine, ai, new String[0]);

            // 创建者需要输入昵称
            GameRegistry.addPendingNickname(userId);

            String msg = "🎮 「" + gameName + "」已创建！"
                + "\n请发送你的昵称开始游戏。"
                + "\n其他玩家请扫码加入（房主发「加入」生成二维码）。";
            bot.sendText(userId, msg);
            return true;
        }

        // "开始游戏" — 只有游戏中的玩家才能调用
        if ((text.equals("开始游戏") || text.equals("开始") || text.contains("开始玩"))
                && GameRegistry.gameState() == GameRegistry.GameState.WAITING) {
            GameSession gs = GameRegistry.session();
            if (gs == null) {
                bot.sendText(userId, "当前没有游戏。");
                return true;
            }
            // 验证调用者是游戏中的玩家
            if (gs.playerName(userId) == null) {
                bot.sendText(userId, "只有当前游戏中的玩家才能开始游戏。");
                return true;
            }
            String ready = GameRegistry.checkReady();
            if (ready == null) {
                bot.sendText(userId, "当前没有游戏。");
                return true;
            }
            if (!"ready".equals(ready)) {
                bot.sendText(userId, "人数不够，无法开始。" + ready);
                return true;
            }
            String announcement = GameRegistry.startGame();
            if (announcement == null) {
                bot.sendText(userId, "游戏已不在等待状态。");
                return true;
            }

            // 谁是卧底：给每个玩家私信发词
            GameEngine eng = GameRegistry.session().engine();
            boolean isUndercover = eng.name().equals("谁是卧底");

            // 广播游戏开始的公告给所有人
            String startMsg = "🎮 游戏开始！\n" + announcement;
            for (String uid : gs.boundUsers()) {
                cluster.sendToUser(uid, startMsg);
            }

            if (isUndercover) {
                var ue = (game.impl.UndercoverEngine) eng;
                // 发词给每个玩家（私信）
                for (String uid : gs.boundUsers()) {
                    String playerName = gs.playerName(uid);
                    String word = ue.getWord(playerName);
                    String wordMsg = "🔒 你的词是：「" + (word != null ? word : "?") + "」\n不要告诉别人！";
                    System.out.println("[游戏] 发词给 " + playerName + "(" + uid + "): " + word);
                    cluster.sendToUser(uid, wordMsg);
                }
                // 触发 AI 开场白并广播给所有玩家
                String opening = gs.prompt("所有玩家已收到词语，游戏正式开始！请宣布游戏开始，"
                    + "说明本轮玩家人数和卧底人数，然后邀请第一位玩家开始描述自己的词语。");
                if (opening != null) {
                    for (String uid : gs.boundUsers()) {
                        cluster.sendToUser(uid, opening);
                    }
                }
            }
            return true;
        }

        // "暂停"
        if (text.equals("暂停") && GameRegistry.gameState() == GameRegistry.GameState.PLAYING) {
            boolean ok = GameRegistry.pauseGame(userId);
            if (!ok) {
                bot.sendText(userId, "你没有剩余暂停次数了（每人2次）");
                return true;
            }
            int remaining = GameRegistry.pauseRemaining(userId);
            // 通知所有人
            for (String uid : GameRegistry.session().boundUsers()) {
                String name = GameRegistry.session().playerName(uid);
                String notice = name.equals(GameRegistry.session().playerName(userId))
                        ? "⏸ 你已暂停游戏，剩余 " + remaining + " 次。"
                        : "⏸ " + GameRegistry.session().playerName(userId) + " 暂停了游戏（剩余" + remaining + "次）。";
                cluster.sendToUser(uid, notice);
            }
            return true;
        }

        // "继续"
        if ((text.equals("继续") || text.equals("恢复"))
                && GameRegistry.gameState() == GameRegistry.GameState.PAUSED) {
            GameRegistry.resumeGame();
            for (String uid : GameRegistry.session().boundUsers()) {
                cluster.sendToUser(uid, "▶️ 游戏已恢复！");
            }
            return true;
        }

        // "结束游戏"
        if (text.equals("结束游戏") && GameRegistry.isRunning()) {
            String gn = GameRegistry.session().engine().name();
            GameRegistry.stop();
            bot.sendText(userId, "🛑 已结束「" + gn + "」。");
            return true;
        }


        return false;
    }
    /**
     * 智能匹配：识别"我想玩X""来一局X""开始X"等自然语言
     * @return 转换后的标准命令，匹配不上返回 null
     */
    private static String smartMatch(String text) {
        String[] triggers = {"我想玩", "来一局", "玩一下", "我玩", "开始玩", "玩个", "我要玩"};

        for (String trigger : triggers) {
            if (text.contains(trigger)) {
                // 提取游戏名：去掉触发词，取第一个词
                String rest = text.substring(text.indexOf(trigger) + trigger.length()).strip();
                String gameName = rest.split("\\s+")[0].replaceAll("[的！!。，,、？?]", "").strip();
                if (!gameName.isBlank()) {
                    // 检查游戏是否存在
                    if (GameRegistry.get(gameName) != null) {
                        return "玩 " + gameName;
                    }
                }
            }
        }
        return null;
    }
}
