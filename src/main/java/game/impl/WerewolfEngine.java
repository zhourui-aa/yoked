package game.impl;

import game.GameEngine;
import game.GameSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 狼人杀引擎 — 9人标准局（3狼人/2村民/预言家/女巫/猎人/守卫）。
 *
 * <p>多人游戏：handle() 返回 null，由 GameSession 自动打标签、统一调 DeepSeek 做全局判断。
 */
public class WerewolfEngine implements GameEngine {

    private static final String[] ROLE_POOL = {
        "狼人", "狼人", "狼人", "村民", "村民",
        "预言家", "女巫", "猎人", "守卫"
    };

    private boolean over;

    @Override public String name() { return "狼人杀"; }
    @Override public int minPlayers() { return 9; }
    @Override public int maxPlayers() { return 9; }

    @Override
    public String systemPrompt() {
        return """
            你是狼人杀的主持人。9人标准局：3狼人、2村民、1预言家、1女巫、1猎人、1守卫。

            你的职责：
            1. 根据每个玩家带标签的发言，判断当前阶段、推动游戏进程
            2. 夜晚阶段：私信通知各角色依次行动
            3. 白天阶段：公布死者、主持讨论、组织投票
            4. 判断胜负条件并宣布结果

            规则：
            - 严禁向任何玩家透露其他人的身份
            - 标注每个玩家发言时应带 [玩家名] 前缀
            - 猎人死亡时可开枪带走一人
            - 女巫解药和毒药各只能用一次
            - 守卫不能连续两晚守同一人
            """;
    }

    @Override
    public String start(GameSession session) {
        List<String> pool = new ArrayList<>(Arrays.asList(ROLE_POOL));
        Collections.shuffle(pool);
        int i = 0;
        for (String name : session.playerNames()) {
            session.setRole(name, pool.get(i++));
        }
        over = false;
        return "角色已分配，进入第1夜。\n请主持人私信告知每位玩家身份。";
    }

    @Override
    public String handle(GameSession session, String userId, String text) {
        // 多人游戏：返回 null，GameSession 自动处理所有标签消息，统一交 DeepSeek 判断
        return null;
    }

    @Override
    public boolean isOver() {
        return over;
    }
}
