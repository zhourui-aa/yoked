package game;

/**
 * 桌游引擎接口 — 加新游戏只需实现此接口。
 */
public interface GameEngine {

    /** 游戏名称 */
    String name();

    /** 规则提示词，喂给 DeepSeek */
    String systemPrompt();

    /** 最少/最多玩家数 */
    int minPlayers();
    int maxPlayers();

    /** 设置玩家列表 */
    void setPlayers(String[] names);

    /** 开始游戏，返回公告文本 */
    String start();

    /** 处理玩家发言，返回 bot 回复 */
    String handle(String userId, String message);

    /** 游戏是否结束 */
    boolean isOver();
}
