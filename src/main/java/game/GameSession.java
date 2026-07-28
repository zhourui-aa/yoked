package game;

import org.example.bot.service.AiService;

import java.util.*;

/**
 * 游戏会话 — 一局游戏中所有玩家的共享 LLM 上下文。
 *
 * <p>核心机制：
 * <ol>
 *   <li>每个玩家消息自动打标签：{@code [张三] 我是预言家，昨晚查了3号}</li>
 *   <li>所有标签消息汇入同一个 DeepSeek 上下文</li>
 *   <li>DeepSeek 看到全局，做出判断后返回</li>
 * </ol>
 *
 * <p>单人和多人游戏的唯一区别是 {@code players} 的大小。
 */
public class GameSession {

    private final GameEngine engine;
    private final AiService ai;
    private final Map<String, String> nameById = new LinkedHashMap<>(); // userId → 玩家名
    private final Map<String, String> roleByName = new LinkedHashMap<>(); // 玩家名 → 角色
    private final List<String> history = new ArrayList<>(); // 共享对话历史
    private final String gameUserId = "game-" + System.currentTimeMillis(); // 游戏专用虚拟用户

    public GameEngine engine() { return engine; }

    public GameSession(GameEngine engine, AiService ai, String[] playerNames) {
        this.engine = engine;
        this.ai = ai;
        for (int i = 0; i < playerNames.length; i++) {
            String name = playerNames[i];
            roleByName.put(name, null); // 角色由引擎 start() 分配
        }
    }

    // ==================== 玩家管理 ====================

    /** 绑定微信 userId 到玩家名（玩家第一次发言时绑定） */
    public void bindUser(String userId, String playerName) {
        nameById.put(userId, playerName);
    }

    /** 设置玩家角色（由引擎在 start 时调用） */
    public void setRole(String playerName, String role) {
        roleByName.put(playerName, role);
    }

    /** userId → 玩家名 */
    public String playerName(String userId) {
        return nameById.get(userId);
    }

    /** 玩家名 → 角色 */
    public String playerRole(String name) {
        return roleByName.get(name);
    }

    /** 所有玩家名 */
    public Set<String> playerNames() {
        return roleByName.keySet();
    }

    /** 所有已绑定的 userId */
    public Set<String> boundUsers() {
        return nameById.keySet();
    }

    /** 是否所有玩家都已绑定 */
    public boolean allBound() {
        return nameById.size() >= roleByName.size();
    }

    // ==================== 对话处理 ====================

    /**
     * 处理一条玩家消息，让 DeepSeek 做全局判断。
     *
     * @param userId 微信 userId
     * @param text   玩家说的话
     * @return DeepSeek 的回复
     */
    public String process(String userId, String text) {
        String name = playerName(userId);
        if (name == null) return null; // 不是这局游戏的玩家

        String role = roleByName.get(name);
        String tag = name + (role != null ? "(" + role + ")" : "");
        history.add("[" + tag + "] " + text);

        // 构建完整上下文：系统提示 + 引擎状态 + 玩家名单 + 对话历史
        StringBuilder ctx = new StringBuilder();
        ctx.append(engine.systemPrompt()).append("\n\n");
        String state = engine.stateContext();
        if (!state.isEmpty()) ctx.append(state).append("\n\n");
        ctx.append("当前玩家：\n");
        for (String n : playerNames()) {
            String r = roleByName.get(n);
            ctx.append("  ").append(n);
            if (r != null) ctx.append("（" + r + "）");
            ctx.append("\n");
        }
        if (!history.isEmpty()) {
            ctx.append("\n游戏对话记录：\n");
            for (String h : history) {
                ctx.append(h).append("\n");
            }
        }

        // 输出日志
        System.out.println("[游戏:" + engine.name() + "] " + tag + " 发言: " + text);

        // 调 DeepSeek
        String reply = ai.chat(gameUserId, ctx.toString());
        history.add("[主持人] " + reply);
        return reply;
    }

    /** GameEngine 直接调用 DeepSeek，用于夜晚阶段等内部逻辑 */
    public String prompt(String promptText) {
        String reply = ai.chat(gameUserId, promptText);
        history.add("[系统] " + promptText + "\n[主持人] " + reply);
        return reply;
    }

    /** 玩家名 → userId（供外部发私信用） */
    public String getUserId(String playerName) {
        for (var e : nameById.entrySet()) {
            if (e.getValue().equals(playerName)) return e.getKey();
        }
        return null;
    }

    /** 清空 GameUserId 的对话历史（避免跨游戏污染） */
    public void clear() {
        ai.getHelpMessage(); // no-op，占位
    }
}
