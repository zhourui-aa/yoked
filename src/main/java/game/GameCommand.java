package game;

import org.example.bot.ilink.ILinkBot;

/**
 * 桌游命令处理 — 从 BotApp 的 tryHandleLocalCommand 中调用。
 */
public class GameCommand {

    /** 处理桌游相关命令，匹配则返回 true */
    public static boolean handle(ILinkBot bot, String userId, String text) {

        // "开启桌游" / "桌游列表" → 列出可用游戏
        if (text.equals("开启桌游") || text.equals("桌游模式") || text.equals("桌游列表")) {
            bot.sendText(userId, "未开启的游戏有：" + GameRegistry.listGames()
                + "\n输入「玩 游戏名 玩家1 玩家2 ...」开始，例如：玩 狼人杀 张三 李四 王五 赵六 孙七 周八 吴九 郑十 冯十一");
            return true;
        }

        // "玩 游戏名 玩家列表" → 开始游戏
        if (text.startsWith("玩 ")) {
            String rest = text.substring(2).strip();
            // 拆分：第一个是游戏名，后面是玩家名
            String[] parts = rest.split("\\s+");
            if (parts.length < 2) {
                bot.sendText(userId, "请指定游戏名和玩家列表，例如：玩 狼人杀 张三 李四 王五 ...");
                return true;
            }
            String gameName = parts[0];
            GameEngine engine = GameRegistry.get(gameName);
            if (engine == null) {
                bot.sendText(userId, "没有「" + gameName + "」这个游戏。\n" + GameRegistry.listGames());
                return true;
            }

            String[] playerNames = new String[parts.length - 1];
            System.arraycopy(parts, 1, playerNames, 0, playerNames.length);
            int count = playerNames.length;

            if (count < engine.minPlayers()) {
                bot.sendText(userId, gameName + "至少需要" + engine.minPlayers() + "人，当前只有" + count + "人。");
                return true;
            }
            if (count > engine.maxPlayers()) {
                bot.sendText(userId, gameName + "最多支持" + engine.maxPlayers() + "人，当前有" + count + "人。");
                return true;
            }

            // 设置玩家并开始
            GameRegistry.start(engine, playerNames);
            String result = engine.start();
            bot.sendText(userId, "🎮 " + gameName + " 开始！" + count + "位玩家已加入。\n" + result);
            System.out.println("[游戏] " + gameName + " 开始，" + count + "人");
            return true;
        }

        // "结束游戏" → 强制结束当前对局
        if (text.equals("结束游戏") && GameRegistry.isRunning()) {
            bot.sendText(userId, "已结束「" + GameRegistry.running().name() + "」。");
            GameRegistry.stop();
            return true;
        }

        return false;
    }
}
