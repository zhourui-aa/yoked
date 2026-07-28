package game.impl;

import game.GameEngine;
import game.GameRegistry;
import game.GameSession;

import java.util.*;

/**
 * 狼人杀引擎 — 支持 6~12 人自定义，引擎跟踪所有关键状态。
 */
public class WerewolfEngine implements GameEngine {

    // === 状态 ===
    private int playerCount;
    private int round;               // 第几轮（一轮 = 一夜 + 一昼）
    private Phase phase;             // 当前阶段
    private boolean over;
    private boolean witchAntidoteUsed;
    private boolean witchPoisonUsed;
    private String guardLastProtected; // 守卫上一晚守护的玩家名
    private final Set<String> dead = new LinkedHashSet<>(); // 已死亡玩家名
    private final List<String> deathLog = new ArrayList<>(); // 死亡记录

    private enum Phase { NIGHT, DAY }

    // 各人数配置：狼人, 村民, 预言家, 女巫, 猎人, 守卫
    private static final int[][] ROLE_CFG = {
        {2,2,1,1,0,0}, {2,2,1,1,1,0}, {2,2,1,1,1,1},
        {3,2,1,1,1,1}, {3,3,1,1,1,1}, {4,3,1,1,1,1}, {4,4,1,1,1,1}
    };
    private static final String[] ROLE_NAMES = {"狼人","村民","预言家","女巫","猎人","守卫"};

    @Override public String name() { return "狼人杀"; }
    @Override public int minPlayers() { return 6; }
    @Override public int maxPlayers() { return 12; }

    // ==================== 规则提示词 ====================

    @Override
    public String systemPrompt() {
        int[] c = ROLE_CFG[playerCount - 6];
        return """
            你是狼人杀主持人。%d人局：%d狼人、%d村民、1预言家、1女巫%s%s。

            【你的职责】根据带标签的发言主持流程、推进阶段、判断结果。

            【黑夜阶段】（按顺序私聊每个角色）：
            1. 守卫 — 私聊询问守护谁（不能连续两晚守同一人）"""
            .formatted(playerCount, c[0], c[1],
                c[4] > 0 ? "、1猎人" : "", c[5] > 0 ? "、1守卫" : "")
            + """
            2. 狼人 — 私聊每个狼人，让他们讨论后统一击杀一人
            3. 女巫 — 告知死者，询问是否用药/毒（各一次）
            4. 预言家 — 私聊询问查验谁，告知其身份

            【白天阶段】：
            1. 公布死者（可能平安夜）
            2. 存活玩家自由讨论
            3. 组织投票放逐（每人一票，票多出局）
            4. 被放逐者若是猎人→询问是否开枪
            5. 公布被放逐者身份

            【胜利条件】所有狼人出局→好人胜；存活狼人数>=存活好人数→狼人胜。

            【格式】公开回复直接说话。私聊用：【私信:玩家名】内容。
            投票时逐个收集，最后宣布「X号被放逐，身份是XX」。
            """;
    }

    // ==================== 状态快照 ====================

    @Override
    public String stateContext() {
        StringBuilder s = new StringBuilder();
        s.append("【当前状态】第").append(round).append("轮，")
         .append(phase == Phase.NIGHT ? "黑夜阶段" : "白天阶段").append("\n");
        s.append("存活：");
        GameSession gs = GameRegistry.session();
        for (String n : gs.playerNames()) {
            if (!dead.contains(n)) s.append(n).append("(").append(gs.playerRole(n)).append(") ");
        }
        s.append("\n已死：").append(dead.isEmpty() ? "无" : String.join(" ", dead));
        s.append("\n女巫解药：").append(witchAntidoteUsed ? "已用" : "未用")
         .append(" 毒药：").append(witchPoisonUsed ? "已用" : "未用");
        if (guardLastProtected != null) s.append(" 守卫昨晚守了：").append(guardLastProtected);
        if (!deathLog.isEmpty()) {
            s.append("\n死亡记录：");
            for (String d : deathLog) s.append("\n  ").append(d);
        }
        return s.toString();
    }

    // ==================== 游戏流程 ====================

    @Override
    public String start(GameSession session) {
        playerCount = (int) session.playerNames().size();
        int[] c = ROLE_CFG[playerCount - 6];
        over = false;
        round = 1;
        phase = Phase.NIGHT;
        witchAntidoteUsed = false;
        witchPoisonUsed = false;
        guardLastProtected = null;
        dead.clear();
        deathLog.clear();

        // 随机分配
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < c.length; i++)
            for (int j = 0; j < c[i]; j++) pool.add(ROLE_NAMES[i]);
        Collections.shuffle(pool);
        int idx = 0;
        StringBuilder rl = new StringBuilder();
        for (String n : session.playerNames()) {
            String role = pool.get(idx++);
            session.setRole(n, role);
            rl.append("  ").append(n).append(" → ").append(role).append("\n");
        }
        System.out.println("[狼人杀] " + playerCount + "人局\n" + rl);

        return "请用【私信:玩家名】给每人发身份。\n\n玩家角色：\n" + rl
            + "\n发完后宣布进入第一夜。\n开始私聊："
            + (c[5] > 0 ? "守卫→" : "") + "狼人→女巫→预言家。"
            + "\n\n⚠ 重要：狼人阶段必须逐一私聊每个狼人收集意见，女巫阶段告知死者后询问是否用药。";
    }

    @Override
    public String handle(GameSession session, String userId, String text) {
        // 从 DeepSeek 回复中检测阶段切换关键词
        String name = session.playerName(userId);
        // 主持人宣布天亮 → 切白天
        if (name == null && (text.contains("天亮了") || text.contains("进入白天") || text.contains("公布死者"))) {
            phase = Phase.DAY;
        }
        // 主持人宣布天黑 → 切黑夜，轮次+1
        if (name == null && (text.contains("天黑了") || text.contains("进入黑夜") || text.contains("进入第"))) {
            phase = Phase.NIGHT;
            round++;
        }
        return null; // 多人游戏，GameSession 自动处理
    }

    @Override public boolean isOver() { return over; }
}
