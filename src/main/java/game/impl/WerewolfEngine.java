package game.impl;

import game.GameEngine;
import game.GameRegistry;
import game.GameSession;

import java.util.*;

/**
 * 狼人杀引擎 — 预女猎白体系，6~12人，屠边规则。
 * 夜间顺序：狼人→女巫→预言家，无守卫。
 */
public class WerewolfEngine implements GameEngine {

    private int playerCount, round;
    private boolean over;
    private boolean night = true; // 游戏从夜晚开始
    private boolean witchAntidoteUsed, witchPoisonUsed;
    private boolean antidoteUsedThisRound;
    private String wolfKillTarget, witchPoisonTarget;
    private String sheriff;
    private boolean hunterCanShoot;
    private final Set<String> dead = new LinkedHashSet<>();

    // {狼人, 平民, 预言家, 女巫, 猎人}
    private static final int[][] ROLE_CFG = {
        {2, 2, 1, 1, 0},  // 6人
        {2, 2, 1, 1, 1},  // 7人
        {3, 2, 1, 1, 1},  // 8人
        {3, 3, 1, 1, 1},  // 9人
        {3, 4, 1, 1, 1},  // 10人
        {4, 4, 1, 1, 1},  // 11人
        {4, 5, 1, 1, 1},  // 12人
    };
    private static final String[] ROLE_NAMES = {"狼人", "平民", "预言家", "女巫", "猎人"};
    private static final int IDX_WOLF = 0, IDX_CIV = 1, IDX_SEER = 2, IDX_WITCH = 3, IDX_HUNTER = 4;

    @Override public String name() { return "狼人杀"; }
    @Override public int minPlayers() { return 6; }
    @Override public int maxPlayers() { return 12; }

    // ==================== 规则 ====================

    @Override
    public String systemPrompt() {
        int[] c = ROLE_CFG[playerCount - 6];
        return ("""
            你是狼人杀主持人。%d人局：%d狼人、%d平民、预言家、女巫%s。

            【私信格式铁则 — 极其重要！违反将导致玩家收不到私信！】
            所有发给特定玩家的私密消息必须独占一行，使用以下格式：
              【私信:玩家名】
              消息内容...
            正确示例：
              【私信:张三】
              你是狼人，你的同伴是李四。今晚你们要商量击杀目标。
            错误示例（引擎无法识别！）：
              ❌ 私信:张三 你是狼人  （缺少【】括号）
              ❌ "私信张三你是狼人"  （缺少冒号和括号标记）
            每条私信的【私信:玩家名】必须是行首第一个字，独占一行或后接内容都可以。

            【铁则】绝不泄露任何玩家身份给其他玩家。
            引擎内部标签（【死者:xxx】【解药已用】【毒药:xxx】【毒药已用】）会被系统自动过滤，玩家不可见。
            不要依赖这些标签来向玩家传递信息——它们是给引擎读的，不是给玩家读的。

            【绝对禁止】天亮之前不得在公开频道透露任何死者信息！
            死者身份在夜晚只能通过【私信:女巫名】告知女巫一人，禁止在公开文字中写出"今晚杀XXX"。

            【屠边胜利】
            - 好人胜：放逐全部狼人 → 游戏立即结束
            - 狼人胜：所有神职(预言家+女巫%s)出局 或 所有平民出局 → 游戏立即结束
            - 存活狼人数≥存活好人数 → 狼人立即胜利

            【角色技能】
            - 狼人：每夜统一投票选1人击杀。多数通过，平票=空刀(无人死亡)。禁止自刀/刀队友。
            - 预言家：每夜查验1人，反馈"好人"或"狼人"。禁止自查/查死人。狼人+女巫行动后最后睁眼。
            - 女巫：解药%s、毒药%s各1次。禁止自救、禁止同晚双开。解药救人>狼刀>毒药。用毒药时必须用【毒药:目标名】指明目标。
            - 猎人%s：被刀/被投票出局可开枪带走1人。被女巫毒死禁止开枪。

            【夜间流程】天黑→狼人睁眼刀人→闭眼→女巫睁眼用药→闭眼→预言家睁眼查验→闭眼→天亮。
            全程闭眼：所有平民、猎人（永久不睁眼）。每次只处理当前角色的行动，等玩家回应后再推进下一步。
            不要在一条回复中跨越多个夜间步骤。

            【死亡结算】同夜狼刀+毒药→双死。空刀+无毒→平安夜。解药救人抵消狼刀。

            【白天流程】
            1. 天亮公布死者(首夜死亡无遗言，其余有遗言)→用自然语言宣布"昨晚xxx被杀"，同时输出【死者:xxx】给引擎（会被过滤）。若无死亡则输出【平安夜】（全员可见）。
            2. 首日→警长竞选：上警发言投票，警长1.5票+最后发言→输出【警长:名字】
            3. 自由讨论(广播)→投票放逐→平票PK再投→再平无人出局→被放逐者有遗言
            4. 猎人在被放逐/被刀后→询问是否开枪

            【一次只做一步】每次只处理当前步骤，等玩家回应再继续。不在同一条回复中跨越多个步骤。

            【状态指令】回复中必须包含引擎标签（会被过滤，玩家不可见）：【死者:xxx】【解药已用】【毒药:xxx】【毒药已用】。玩家可见标签：【平安夜】【警长:xxx】【游戏结束:理由】
            """)
            .formatted(playerCount, c[IDX_WOLF], c[IDX_CIV],
                c[IDX_HUNTER] > 0 ? "、猎人" : "",
                c[IDX_HUNTER] > 0 ? "+猎人" : "",
                witchAntidoteUsed ? "已用" : "○", witchPoisonUsed ? "已用" : "○",
                c[IDX_HUNTER] > 0 ? "" : "(本局无)");
    }

    // ==================== 状态 ====================

    @Override
    public String stateContext() {
        var gs = GameRegistry.session();
        var sb = new StringBuilder();
        sb.append("【第").append(round).append("轮·").append(night ? "🌙黑夜" : "☀️白天").append("】");
        sb.append("存活(").append(playerCount - dead.size()).append(")：");
        for (String n : gs.playerNames())
            if (!dead.contains(n)) sb.append(n).append("(").append(gs.playerRole(n)).append(") ");
        sb.append("\n死者：").append(dead.isEmpty() ? "无" : String.join(" ", dead));
        sb.append(" | 解药").append(witchAntidoteUsed ? "×" : "○")
          .append(" 毒药").append(witchPoisonUsed ? "×" : "○");
        if (wolfKillTarget != null) sb.append(" 狼刀:").append(wolfKillTarget);
        if (witchPoisonTarget != null) sb.append(" 毒:").append(witchPoisonTarget);
        if (sheriff != null) sb.append(" 警长:").append(sheriff);
        if (!hunterCanShoot) sb.append(" 猎人禁枪");
        return sb.toString();
    }

    // ==================== 游戏流程 ====================

    @Override
    public String start(GameSession session) {
        playerCount = session.playerNames().size();
        int[] c = ROLE_CFG[playerCount - 6];
        over = false; round = 1; night = true;
        witchAntidoteUsed = false; witchPoisonUsed = false;
        antidoteUsedThisRound = false;
        wolfKillTarget = null; witchPoisonTarget = null;
        sheriff = null; hunterCanShoot = true;
        dead.clear();

        List<String> pool = new ArrayList<>();
        for (int i = 0; i < c.length; i++)
            for (int j = 0; j < c[i]; j++) pool.add(ROLE_NAMES[i]);
        Collections.shuffle(pool);
        int idx = 0;
        var rl = new StringBuilder();
        for (String n : session.playerNames()) {
            session.setRole(n, pool.get(idx++));
            rl.append("  ").append(n).append(" → ").append(session.playerRole(n)).append("\n");
        }
        System.out.println("[狼人杀] " + playerCount + "人\n" + rl);
        return """
            角色已分配：
            %s
            请用【私信:玩家名】告知每位玩家身份。狼人要告知同伴是谁。
            只说自己的身份，不透露他人角色。告知完毕后输出"角色通知完毕"。""".formatted(rl.toString());
    }

    /** 角色分配完后启动第一夜 */
    public String nightTrigger() {
        return """
            现在是第1夜🌙。严格按照以下三步执行，每步只做一个角色的行动，绝不跳步！

            ⚠️ 核心规则：
            - 天亮前绝不公开死者身份！死者信息只能通过【私信:女巫】告知！
            - 每条私信用【私信:玩家名】独占一行开头！

            === 第1步：狼人行动 ===
            向全体公告："狼人请睁眼。"
            现在等待狼人们在群内讨论击杀目标（你是主持人，你只需等待和观察，不要在狼人讨论中插话）。
            当存活狼人统一目标后，输出【死者:XXX】（引擎标签，会被过滤，玩家不可见）。
            然后用【私信:女巫名】告知女巫："今晚死者是XXX"（只有女巫能看到）。
            公告："狼人请闭眼。"

            === 第2步：女巫行动 ===
            公告："女巫请睁眼。"
            用【私信:女巫名】告知死者（如果狼人空刀则说平安夜），询问是否使用解药/毒药。
            等待女巫回应。
            用解药→输出【解药已用】。用毒药→输出【毒药:目标名】和【毒药已用】。
            公告："女巫请闭眼。"

            === 第3步：预言家行动 ===
            公告："预言家请睁眼。"
            用【私信:预言家名】询问查验谁，等待预言家指定目标后，用【私信:预言家名】告知"好人"或"狼人"。
            公告："预言家请闭眼。"

            三步全部完成后→公告"天亮了"+【进入白天】+公布昨晚死者（如"昨晚XXX被杀"）或【平安夜】。

            ⚠️ 从现在开始只做第1步！等狼人讨论完再推进！""";
    }

    @Override
    public String handle(GameSession session, String userId, String text) {
        for (String line : text.split("\n")) {
            line = line.strip();
            if (line.contains("【死者:")) {
                String name = extractCmd(line, "【死者:");
                if (name != null && !dead.contains(name)) {
                    dead.add(name);
                    if (wolfKillTarget == null) wolfKillTarget = name;
                }
            }
            if (line.contains("【解药已用】")) { witchAntidoteUsed = true; antidoteUsedThisRound = true; }
            if (line.contains("【毒药已用】")) witchPoisonUsed = true;
            if (line.contains("【毒药:")) {
                String name = extractCmd(line, "【毒药:");
                if (name != null) witchPoisonTarget = name;
            }
            if (line.contains("【警长:")) sheriff = extractCmd(line, "【警长:");
            if (line.contains("进入白天") || line.contains("天亮了")) {
                night = false;
                // 解药救人
                if (antidoteUsedThisRound && wolfKillTarget != null) {
                    dead.remove(wolfKillTarget);
                    System.out.println("[狼人杀] 解药救活 " + wolfKillTarget);
                }
                // 毒药杀人
                if (witchPoisonTarget != null && !dead.contains(witchPoisonTarget)) {
                    dead.add(witchPoisonTarget);
                    if ("猎人".equals(session.playerRole(witchPoisonTarget))) hunterCanShoot = false;
                    System.out.println("[狼人杀] 毒杀 " + witchPoisonTarget);
                }
                // 猎人被刀后也被狼刀标记，检查并禁枪
                if (wolfKillTarget != null && dead.contains(wolfKillTarget)
                    && "猎人".equals(session.playerRole(wolfKillTarget))
                    && witchPoisonTarget == null) {
                    hunterCanShoot = true; // 纯狼刀 → 可开枪
                }
                wolfKillTarget = null; witchPoisonTarget = null;
                antidoteUsedThisRound = false;
            }
            if (line.contains("进入黑夜") || line.contains("天黑了")) {
                night = true; round++;
                antidoteUsedThisRound = false;
                wolfKillTarget = null; witchPoisonTarget = null;
            }
            if (line.contains("游戏结束")) over = true;
        }
        return null;
    }

    @Override public boolean isNight() { return night; }
    @Override public boolean isOver() { return over; }
    @Override public boolean isPlayerAlive(String playerName) { return !dead.contains(playerName); }

    private static String extractCmd(String line, String prefix) {
        int s = line.indexOf(prefix) + prefix.length();
        int e = line.indexOf("】", s);
        if (s < prefix.length() || e < 0) return null;
        String v = line.substring(s, e).strip();
        return v.isEmpty() ? null : v;
    }
}
