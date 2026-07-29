package game;

/**
 * 桌游引擎接口 — 组员只需实现此接口，不用碰 BotApp。
 *
 * <h3>实现步骤（以狼人杀为例）</h3>
 * <ol>
 *   <li>写 systemPrompt() — 返回规则描述，喂给 DeepSeek</li>
 *   <li>写 start(session) — 分配角色，给每人发私信</li>
 *   <li>写 handle(session, userId, text) — 处理发言，</li>
 *      单人游戏在这里写和 AI 交互的逻辑，
 *      多人游戏直接返回 {@code null}，让 GameSession 自动做全局处理
 *   <li>写 isOver() — 返回游戏是否结束</li>
 * </ol>
 */
public interface GameEngine {

    /** 游戏名称 */
    String name();

    /** 规则提示词（角色、阶段、判断逻辑），喂给 DeepSeek 做 system prompt */
    String systemPrompt();

    /** 最少/最多玩家数（单人游戏两个都填 1） */
    int minPlayers();
    int maxPlayers();

    /**
     * 开始游戏。
     * 实现者在这里分配角色、发初始私信。
     * @param session 游戏会话
     * @return 群发公告文本
     */
    String start(GameSession session);

    /**
     * 处理玩家发言（每句话都会调一次）。
     * <p>对于多人游戏，通常返回 {@code null} 即可——
     *    GameSession 会自动把所有玩家的话打上标签，统一发给 DeepSeek 做全局判断。
     * <p>对于单人游戏，可以在这里写和 AI 的交互逻辑。
     *
     * @return AI 的回复文本，返回 null 则由 GameSession 代劳
     */
    String handle(GameSession session, String userId, String text);

    /**
     * 当前游戏状态快照（注入到 DeepSeek 上下文中）。
     * 返回空字符串表示无需额外状态。
     */
    default String stateContext() { return ""; }

    /** 游戏是否结束 */
    boolean isOver();
}
