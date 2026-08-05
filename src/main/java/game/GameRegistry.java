package game;

import game.impl.TurtleSoupEngine;
import game.impl.UndercoverEngine;
import game.impl.WerewolfEngine;
import game.impl.LifeSimEngine;
import game.impl.CodeBreakerEngine;
import org.example.bot.service.AiService;

import java.util.*;

/**
 * 游戏注册中心 — 引擎注册、大厅、进行中的会话。
 */
public class GameRegistry {

    private static final Map<String, GameEngine> engines = new LinkedHashMap<>();
    private static volatile GameLobby lobby;
    private static volatile GameSession running;

    // 自动注册所有内置引擎，无需在 BotApp 中手动注册
    static {
        register(new WerewolfEngine());
        register(new TurtleSoupEngine());
        register(new UndercoverEngine());
        register(new LifeSimEngine());
        register(new CodeBreakerEngine());
    }

    // ==================== 引擎 ====================

    public static void register(GameEngine engine) { engines.put(engine.name(), engine); }

    public static String listGames() {
        if (engines.isEmpty()) return "暂无可用桌游。";
        var sb = new StringBuilder();
        for (var g : engines.values())
            sb.append("\n  • ").append(g.name())
              .append("（").append(g.minPlayers()).append("-").append(g.maxPlayers()).append("人）");
        return sb.toString();
    }

    public static GameEngine get(String name) { return engines.get(name); }

    /** 返回所有已注册游戏的名称列表 */
    public static List<String> gameNames() {
        return new ArrayList<>(engines.keySet());
    }

    // ==================== 大厅 ====================

    public static boolean hasLobby() { return lobby != null; }
    public static GameLobby lobby() { return lobby; }

    public static void createLobby(GameEngine engine, int slots, String creatorId, String creatorBotName) {
        lobby = new GameLobby(engine, slots, creatorId, creatorBotName);
    }

    public static void dismissLobby() { lobby = null; }

    // ==================== 会话 ====================

    public static boolean isRunning() { return running != null && !running.engine().isOver(); }
    public static GameSession session() { return running; }

    public static void start(GameEngine engine, AiService ai, String[] playerNames) {
        running = new GameSession(engine, ai, playerNames);
    }

    public static void stop() { running = null; }

    // ==================== 大厅数据类 ====================

    public static class GameLobby {
        public final GameEngine engine;
        public final int slots;
        public final String creatorId;
        public final String creatorBotName;
        private final Map<String, String> bound = new LinkedHashMap<>();   // 昵称 → userId（已扫码）
        private final LinkedHashSet<String> pending = new LinkedHashSet<>(); // 待扫码的昵称

        GameLobby(GameEngine engine, int slots, String creatorId, String creatorBotName) {
            this.engine = engine;
            this.slots = slots;
            this.creatorId = creatorId;
            this.creatorBotName = creatorBotName;
        }

        /** 预订一个位置，玩家待扫码 */
        public boolean reserve(String nickname) {
            if (bound.size() + pending.size() >= slots) return false;
            if (bound.containsKey(nickname) || pending.contains(nickname)) return false;
            pending.add(nickname);
            return true;
        }

        /** 创建者直接绑定（不经过pending） */
        public boolean bindDirect(String nickname, String userId) {
            if (bound.containsKey(nickname)) return false;
            bound.put(nickname, userId);
            return true;
        }

        /** 玩家扫码后绑定 userId */
        public boolean bind(String nickname, String userId) {
            if (!pending.contains(nickname)) return false;
            pending.remove(nickname);
            bound.put(nickname, userId);
            return true;
        }

        public int boundCount() { return bound.size(); }
        public int pendingCount() { return pending.size(); }
        public int totalJoined() { return bound.size() + pending.size(); }

        /** 是否所有人都已扫码 */
        public boolean allBound() { return bound.size() >= slots && pending.isEmpty(); }

        public Set<String> nicknames() { return new LinkedHashSet<>(bound.keySet()); }

        public boolean pendingContains(String name) { return pending.contains(name); }
        /** 所有待扫码的昵称（用于清理时关闭对应 bot） */
        public Set<String> pendingNames() { return new LinkedHashSet<>(pending); }
        public String[] toPlayerNames() { return bound.keySet().toArray(new String[0]); }
        public Map<String, String> boundMap() { return Collections.unmodifiableMap(bound); }

        /** 大厅全貌 */
        public String status() {
            var sb = new StringBuilder();
            sb.append("🏠 ").append(engine.name()).append("（").append(totalJoined()).append("/").append(slots).append("）\n");
            int i = 1;
            for (var e : bound.entrySet()) {
                sb.append("  ").append(i++).append(". ").append(e.getKey()).append(" ✅ 已就位\n");
            }
            for (String n : pending) {
                sb.append("  ").append(i++).append(". ").append(n).append(" ⏳ 请在终端扫码\n");
            }
            return sb.toString();
        }
    }
}