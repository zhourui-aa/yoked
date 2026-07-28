package game;

import org.example.bot.ilink.ILinkBot;
import org.example.bot.service.AiService;

/**
 * 桌游命令处理 — 从 BotApp 的 tryHandleLocalCommand 中调用。
 */
public class GameCommand {

    /** 处理桌游相关命令，匹配则返回 true */
    public static boolean handle(ILinkBot bot, AiService ai, String userId, String text) {

        // "开启桌游" / "桌游列表"
        if (text.equals("开启桌游") || text.equals("桌游模式") || text.equals("桌游列表")) {
            bot.sendText(userId, "🎮 可玩的桌游：" + GameRegistry.listGames()
                + "\n\n输入「玩 游戏名 你的昵称 玩家2 玩家3 ...」开始游戏。\n单人游戏只需：玩 游戏名 你的昵称");
            return true;
        }

        // "加入 游戏名 你的昵称" — 加入进行中的多人游戏
        if (text.startsWith("加入 ") && GameRegistry.isRunning()) {
            String name = text.substring(3).strip();
            if (name.isBlank()) {
                bot.sendText(userId, "请输入你的昵称，例如：加入 张三");
                return true;
            }
            GameRegistry.session().bindUser(userId, name);
            bot.sendText(userId, "✅ " + name + " 已加入「" + GameRegistry.session().engine().name() + "」！");
            return true;
        }

        // "玩 游戏名 玩家列表"
        if (text.startsWith("玩 ")) {
            String rest = text.substring(2).strip();
            String[] parts = rest.split("\\s+");
            if (parts.length < 2) {
                bot.sendText(userId, "请指定游戏名和至少你的昵称。\n单人游戏：玩 海龟汤 张三\n多人游戏：玩 狼人杀 张三 李四 王五 ...");
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
                bot.sendText(userId, gameName + " 至少需要" + engine.minPlayers() + "人，当前只有" + count + "人。");
                return true;
            }
            if (count > engine.maxPlayers()) {
                bot.sendText(userId, gameName + " 最多支持" + engine.maxPlayers() + "人，当前有" + count + "人。");
                return true;
            }

            // 创建游戏会话
            GameRegistry.start(engine, ai, playerNames);
            GameSession session = GameRegistry.session();

            // 发起人自动绑定第一个玩家
            session.bindUser(userId, playerNames[0]);

            // 引擎初始化
            String announcement = engine.start(session);
            bot.sendText(userId, "🎮 「" + gameName + "」开始！" + count + "位玩家。\n" + announcement);

            // 多人游戏提示还需加入
            if (count > 1) {
                bot.sendText(userId, "📢 其他玩家请发送「加入 你的昵称」进入游戏。");
            }
            System.out.println("[游戏] " + gameName + " 开始，" + count + "人");
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
}
