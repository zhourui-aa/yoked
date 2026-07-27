package game.impl;

import game.GameEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 狼人杀引擎 — 9人标准局（3狼人/2村民/预言家/女巫/猎人/守卫）。
 */
public class WerewolfEngine implements GameEngine {

    private static final String[] ROLE_POOL = {
        "狼人", "狼人", "狼人", "村民", "村民",
        "预言家", "女巫", "猎人", "守卫"
    };

    private String[] playerNames;
    private String[] roles;
    private boolean over;

    @Override public String name() { return "狼人杀"; }
    @Override public int minPlayers() { return 9; }
    @Override public int maxPlayers() { return 9; }

    @Override
    public String systemPrompt() {
        return "你是狼人杀主持人，9人局：3狼人/2村民/1预言家/1女巫/1猎人/1守卫。";
    }

    @Override
    public void setPlayers(String[] names) {
        this.playerNames = names;
    }

    @Override
    public String start() {
        // 洗牌分配角色
        List<String> pool = new ArrayList<>(Arrays.asList(ROLE_POOL));
        Collections.shuffle(pool);
        roles = pool.toArray(new String[0]);
        over = false;

        // 给每个玩家发身份
        StringBuilder result = new StringBuilder("角色已分配：\n");
        for (int i = 0; i < playerNames.length; i++) {
            result.append("  ").append(i + 1).append("号 ").append(playerNames[i])
                  .append(" 的身份已私聊告知\n");
        }
        result.append("\n游戏开始！进入第一夜...");
        return result.toString();
    }

    @Override
    public String handle(String userId, String message) {
        return "";
    }

    @Override
    public boolean isOver() {
        return over;
    }
}
