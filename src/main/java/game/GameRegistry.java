package game;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游戏注册中心 — 管理可用的桌游和进行中的对局。
 */
public class GameRegistry {

    private static final Map<String, GameEngine> engines = new LinkedHashMap<>();
    private static volatile GameEngine running;

    /** 注册一个桌游引擎 */
    public static void register(GameEngine engine) {
        engines.put(engine.name(), engine);
    }

    /** 列出所有可用游戏 */
    public static String listGames() {
        if (engines.isEmpty()) return "暂无可用桌游。";
        StringBuilder sb = new StringBuilder();
        for (GameEngine g : engines.values()) {
            sb.append("\n  • ").append(g.name())
              .append("（").append(g.minPlayers()).append("-").append(g.maxPlayers()).append("人）");
        }
        return sb.toString();
    }

    /** 按名称查找引擎 */
    public static GameEngine get(String name) {
        return engines.get(name);
    }

    /** 当前是否有一局正在运行 */
    public static boolean isRunning() {
        return running != null && !running.isOver();
    }

    /** 获取正在运行的游戏 */
    public static GameEngine running() {
        return running;
    }

    /** 开始一局游戏 */
    public static void start(GameEngine engine, String[] playerNames) {
        engine.setPlayers(playerNames);
        running = engine;
    }

    /** 结束当前游戏 */
    public static void stop() {
        running = null;
    }
}
