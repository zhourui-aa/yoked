package game;

import org.example.bot.service.AiService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏注册中心 — 管理可用的桌游引擎和正在进行中的游戏会话。
 */
public class GameRegistry {

    /** 游戏状态 */
    public enum GameState {
        WAITING,    // 等人加入，还没开始
        PLAYING,    // 游戏进行中
        PAUSED      // 已暂停
    }

    private static volatile GameState state;
    private static final java.util.Map<String, Integer> pauseCounts = new java.util.concurrent.ConcurrentHashMap<>();
    /** 等待输入昵称的用户 */
    private static final java.util.Set<String> pendingNicknames = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final Map<String, GameEngine> engines = new LinkedHashMap<>();
    private static volatile GameSession running;

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
        return running != null && !running.engine().isOver();
    }

    /** 获取当前游戏会话 */
    public static GameSession session() {
        return running;
    }

    /** 开始一局游戏 */
    public static void start(GameEngine engine, AiService ai, String[] playerNames) {
        running = new GameSession(engine, ai, playerNames);
        state = GameState.WAITING;
        pauseCounts.clear();
        pendingNicknames.clear();
    }

    /** 结束当前游戏 */
    public static void stop() {
        running = null;
        state = null;
        pendingNicknames.clear();
        pauseCounts.clear();
    }

    /** 所有人发"开始游戏"时调用，只有 WAITING 状态才能开始。返回公告文本 */
    public static String startGame() {
        if (state != GameState.WAITING) return null;
        String announcement = running.engine().start(running);
        state = GameState.PLAYING;
        for (String uid : running.boundUsers()) {
            pauseCounts.put(uid, 2);  // 每人 2 次暂停机会
        }
        return announcement;
    }

    /** 玩家请求暂停 */
    public static boolean pauseGame(String userId) {
        if (state != GameState.PLAYING) return false;
        int remaining = pauseCounts.getOrDefault(userId, 0);
        if (remaining <= 0) return false;
        pauseCounts.put(userId, remaining - 1);
        state = GameState.PAUSED;
        return true;
    }

    /** 恢复游戏 */
    public static boolean resumeGame() {
        if (state != GameState.PAUSED) return false;
        state = GameState.PLAYING;
        return true;
    }

    /** 检查当前游戏是否可以进行（人数够等） */
    public static String checkReady() {
        if (running == null) return null;
        int current = running.boundUsers().size();
        int min = running.engine().minPlayers();
        int max = running.engine().maxPlayers();
        if (current < min) return "还需要 " + (min - current) + " 人才够。";
        if (current > max) return "人数超限，最多 " + max + " 人。";
        return "ready";
    }

    /** 获取当前游戏状态 */
    public static GameState gameState() { return state; }

    /** 获取某玩家的暂停剩余次数 */
    public static int pauseRemaining(String userId) {
        return pauseCounts.getOrDefault(userId, 0);
    }

    /** 游戏状态字符串 */
    public static String statusText() {
        if (running == null) return "没有游戏";
        int cur = running.boundUsers().size();
        int min = running.engine().minPlayers();
        int max = running.engine().maxPlayers();
        String s = state == GameState.WAITING ? "⏳ 等待中"
                : state == GameState.PLAYING ? "🎮 进行中"
                : "⏸ 已暂停";
        return s + " " + running.engine().name() + " " + cur + "/" + min + "-" + max + "人";
    }

    // ==================== 昵称输入管理 ====================

    /** 标记该用户需要输入昵称 */
    public static void addPendingNickname(String userId) {
        pendingNicknames.add(userId);
    }

    /** 检查用户是否在等待输入昵称 */
    public static boolean isPendingNickname(String userId) {
        return pendingNicknames.contains(userId);
    }

    /** 用户已完成昵称输入 */
    public static void removePendingNickname(String userId) {
        pendingNicknames.remove(userId);
    }

    /** 清理所有等待昵称的用户 */
    public static void clearPending() {
        pendingNicknames.clear();
    }
}
