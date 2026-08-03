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
    private String pendingAnnouncement; // handle() 返回的阶段公告
    // 狼人共识
    private String wolfProposal;           // 提议击杀目标
    private String wolfProposer;           // 谁提议的
    private final Set<String> wolfAgreed = new LinkedHashSet<>(); // 已同意的狼人

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

    // ==================== 阶段机 ====================

    /** 夜晚子阶段 */
    public enum NightPhase { WOLVES, WITCH, SEER, DONE }
    private NightPhase nightPhase = NightPhase.WOLVES;
    public NightPhase getNightPhase() { return nightPhase; }

    /** 检查玩家在夜间是否允许发言（只有当前活跃角色可以） */
    public boolean canSpeakAtNight(String playerName, GameSession session) {
        if (!night) return true; // 白天都可以
        if (nightPhase == NightPhase.DONE) return false; // 夜晚结束但还没天亮
        String role = session.playerRole(playerName);
        return switch (nightPhase) {
            case WOLVES -> "狼人".equals(role);
            case WITCH -> "女巫".equals(role);
            case SEER -> "预言家".equals(role);
            default -> false;
        };
    }

    /** 获取当前阶段允许发言的角色名 */
    public String activeRoleName() {
        if (!night) return "所有人";
        return switch (nightPhase) {
            case WOLVES -> "狼人";
            case WITCH -> "女巫";
            case SEER -> "预言家";
            default -> "无";
        };
    }

    // ==================== 规则 ====================

    @Override
    public String systemPrompt() {
        int[] c = ROLE_CFG[playerCount - 6];
        return ("""
            你是狼人杀主持人（仅负责狼人阶段）。%d人局：%d狼人、%d平民、预言家、女巫%s。

            【你的唯一职责】监听狼人讨论，判断他们何时达成击杀共识。
            当狼人统一目标后，输出【死者:XXX】和【狼人行动结束】。
            不要在回复中加入任何其他文字——系统会自动处理所有玩家通信。

            【禁止事项】
            - 禁止使用【私信:XXX】标签
            - 禁止替狼人做决定
            - 禁止在狼人未达成一致时输出标签

            【状态指令（仅输出这些）】【死者:XXX】【狼人行动结束】
            """)
            .formatted(playerCount, c[IDX_WOLF], c[IDX_CIV],
                c[IDX_HUNTER] > 0 ? "、猎人" : "",
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
        nightPhase = NightPhase.WOLVES;
        pendingAnnouncement = null;
        wolfProposal = null; wolfProposer = null; wolfAgreed.clear();
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
        // 角色由系统直接私发，不经过 AI 生成角色卡
        return null;
    }

    /** 生成玩家的角色卡文本（系统直发，不依赖 AI） */
    public String buildRoleCard(String playerName) {
        GameSession gs = GameRegistry.session();
        String role = gs != null ? gs.playerRole(playerName) : null;
        if (role == null) return "身份信息缺失";
        StringBuilder sb = new StringBuilder();
        sb.append("🃏 你的身份是：").append(role).append("\n\n");
        switch (role) {
            case "狼人" -> {
                java.util.List<String> partners = new java.util.ArrayList<>();
                if (gs != null) for (String n : gs.playerNames()) {
                    if ("狼人".equals(gs.playerRole(n)) && !n.equals(playerName))
                        partners.add(n);
                }
                sb.append(partners.isEmpty()
                    ? "你是独狼。\n"
                    : "你的同伴是：" + String.join("、", partners) + "。\n");
                sb.append("每晚和同伴商量击杀一名玩家。多数通过，平票则空刀（无人死亡）。\n");
                sb.append("禁止自刀或刀队友。");
            }
            case "平民" -> sb.append("你没有特殊技能。好好推理，找出狼人，投票放逐他们。");
            case "预言家" -> sb.append("每晚可以查验一名玩家的身份（反馈「好人」或「狼人」）。\n禁止自查或查死人。");
            case "女巫" -> sb.append("你有一瓶解药和一瓶毒药，各只能使用一次。\n解药不能自救。解药和毒药不能在同一晚使用。");
            case "猎人" -> sb.append("被狼人杀死或被投票放逐时，可以开枪带走一名玩家。\n但被女巫毒死时不能开枪。");
        }
        return sb.toString();
    }

    /** 角色分配完后启动第一夜——狼人阶段 */
    public String nightTrigger() {
        return "现在是第" + round + "夜🌙。等待狼人讨论击杀目标。统一后输出【死者:XXX】【狼人行动结束】。不要加入其他文字。";
    }

    // ==================== 狼人共识（系统驱动） ====================

    /**
     * 处理狼人阶段指令。返回结果消息，null=普通聊天（广播给同伴即可）。
     */
    public String handleWolfCommand(String speaker, String text, GameSession session) {
        String cmd = text.strip();
        // 提议击杀
        if (cmd.startsWith("杀") || cmd.startsWith("杀 ")) {
            String target = cmd.startsWith("杀 ") ? cmd.substring(2).strip() : cmd.substring(1).strip();
            if (target.isEmpty()) return "❌ 请输入「杀 玩家名」。";
            if (!session.playerNames().contains(target)) return "❌ 没有这个玩家。";
            if (!isPlayerAlive(target)) return "❌ " + target + " 已经死了。";
            if ("狼人".equals(session.playerRole(target))) return "❌ 不能杀狼人同伴。";
            wolfProposal = target;
            wolfProposer = speaker;
            wolfAgreed.clear();
            wolfAgreed.add(speaker);
            // 告诉其他狼人
            StringBuilder sb = new StringBuilder();
            sb.append("🐺 ").append(speaker).append(" 提议击杀 ").append(target).append("。\n");
            sb.append("其他狼人请回复「同意」或「不同意」。");
            return sb.toString();
        }
        // 同意
        if (cmd.equals("同意")) {
            if (wolfProposal == null) return "❌ 还没有人提议击杀目标。";
            if (wolfAgreed.contains(speaker)) return "❌ 你已经同意过了。";
            wolfAgreed.add(speaker);
            // 检查是否所有狼人都同意
            long wolfCount = session.playerNames().stream()
                .filter(n -> "狼人".equals(session.playerRole(n)) && isPlayerAlive(n)).count();
            if (wolfAgreed.size() >= wolfCount) {
                // 共识达成——只设目标，天亮才进死亡池（保证女巫/预言家回合还能发言）
                String killed = wolfProposal;
                wolfKillTarget = killed;
                wolfProposal = null; wolfProposer = null; wolfAgreed.clear();
                nightPhase = NightPhase.WITCH;
                return "✅ 狼人一致同意击杀 " + killed + "。\n🐺 狼人请闭眼。\n🔮 女巫请睁眼。";
            }
            return "✅ 你已同意击杀 " + wolfProposal + "。（" + wolfAgreed.size() + "/" + wolfCount + "）";
        }
        // 不同意
        if (cmd.equals("不同意") || cmd.equals("反对")) {
            if (wolfProposal == null) return "❌ 还没有人提议击杀目标。";
            wolfProposal = null; wolfProposer = null; wolfAgreed.clear();
            return "🔄 提议被否决。请重新讨论击杀目标。";
        }
        return null; // 普通聊天
    }

    /** 告知女巫死者信息（系统直发） */
    public String witchInfoMessage() {
        String target = wolfKillTarget;
        if (target != null) {
            return "🔮 今晚死者是 " + target + "。\n输入「救」使用解药，输入「毒 玩家名」使用毒药，输入「不用」跳过。";
        }
        return "🔮 今晚是平安夜，无人死亡。\n输入「毒 玩家名」使用毒药，输入「不用」跳过。";
    }

    /**
     * 解析女巫指令，应用效果，返回结果消息（null=无效指令）
     * @return 发给女巫的确认消息 + 阶段公告拼接
     */
    public String handleWitchCommand(String text, GameSession session) {
        String cmd = text.strip();
        // 解药
        if (cmd.equals("救")) {
            if (witchAntidoteUsed) return "❌ 解药已用过。";
            if (wolfKillTarget == null) return "❌ 今晚没有死者，无需使用解药。";
            witchAntidoteUsed = true;
            antidoteUsedThisRound = true;
            System.out.println("[狼人杀] 女巫使用解药，救活 " + wolfKillTarget);
            return advanceToSeer();
        }
        // 毒药
        if (cmd.startsWith("毒")) {
            if (witchPoisonUsed) return "❌ 毒药已用过。";
            String target = cmd.substring(1).strip();
            if (target.isEmpty()) return "❌ 请输入「毒 玩家名」。";
            if (!session.playerNames().contains(target)) return "❌ 没有这个玩家。";
            if (!isPlayerAlive(target)) return "❌ " + target + " 已经死了。";
            witchPoisonUsed = true;
            witchPoisonTarget = target;
            System.out.println("[狼人杀] 女巫使用毒药，毒杀 " + target);
            return "✅ 你决定毒杀 " + target + "。\n" + advanceToSeer();
        }
        // 跳过
        if (cmd.equals("不用")) {
            System.out.println("[狼人杀] 女巫跳过");
            return advanceToSeer();
        }
        return null; // 无效指令
    }

    /** 推进到预言家阶段，返回阶段公告 */
    private String advanceToSeer() {
        nightPhase = NightPhase.SEER;
        return "🔮 女巫请闭眼。\n🔍 预言家请睁眼。";
    }

    /**
     * 处理预言家查验目标（系统查角色，不经过AI）
     * @return 发给预言家的结果 + 阶段公告
     */
    public String handleSeerTarget(String text, GameSession session) {
        String target = text.strip();
        if (!session.playerNames().contains(target)) return "❌ 没有这个玩家，请重新输入。";
        if (!isPlayerAlive(target)) return "❌ " + target + " 已经死了，请选择存活玩家。";
        String role = session.playerRole(target);
        String result = "狼人".equals(role) ? "【狼人】" : "【好人】";
        // 推进到天亮
        nightPhase = NightPhase.DONE;
        night = false;
        // 死亡结算（天亮统一进死亡池）
        String wolfTarget = wolfKillTarget;
        String poisonTarget = witchPoisonTarget;
        if (antidoteUsedThisRound && wolfTarget != null) {
            wolfTarget = null; // 被救活，不进死亡池
            System.out.println("[狼人杀] 解药救活");
        }
        if (wolfTarget != null) {
            dead.add(wolfTarget); // 狼刀生效
        }
        if (poisonTarget != null && !dead.contains(poisonTarget)) {
            dead.add(poisonTarget);
            if ("猎人".equals(session.playerRole(poisonTarget))) hunterCanShoot = false;
        }
        // 构造天亮公告（只报今夜死者）
        java.util.List<String> nightDead = new java.util.ArrayList<>();
        if (wolfTarget != null) nightDead.add(wolfTarget);
        if (poisonTarget != null && !poisonTarget.equals(wolfTarget)) nightDead.add(poisonTarget);
        String dawnAnnounce = "🔍 预言家请闭眼。\n\n☀️ 天亮了！";
        if (!nightDead.isEmpty()) {
            dawnAnnounce += "\n昨晚 " + String.join("、", nightDead) + " 被杀。";
        } else {
            dawnAnnounce += "\n昨晚是平安夜，无人死亡。";
        }
        wolfKillTarget = null; witchPoisonTarget = null;
        antidoteUsedThisRound = false;
        return "🔍 查验结果：" + target + " 是 " + result + "。\n\n" + dawnAnnounce;
    }

    @Override
    public String handle(GameSession session, String userId, String text) {
        pendingAnnouncement = null;
        for (String line : text.split("\n")) {
            line = line.strip();
            // 引擎标签解析
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
            // 夜晚阶段切换
            if (line.contains("【狼人行动结束】") && nightPhase == NightPhase.WOLVES
                && wolfKillTarget != null) { // 必须有击杀目标才能结束
                nightPhase = NightPhase.WITCH;
                pendingAnnouncement = "🐺 狼人请闭眼。\n🔮 女巫请睁眼。";
            }
            if (line.contains("【女巫行动结束】") && nightPhase == NightPhase.WITCH) {
                nightPhase = NightPhase.SEER;
                pendingAnnouncement = "🔮 女巫请闭眼。\n🔍 预言家请睁眼。";
            }
            if (line.contains("【预言家行动结束】") && nightPhase == NightPhase.SEER) {
                nightPhase = NightPhase.DONE;
                night = false;
                // Apply night deaths
                if (antidoteUsedThisRound && wolfKillTarget != null) {
                    dead.remove(wolfKillTarget);
                    System.out.println("[狼人杀] 解药救活 " + wolfKillTarget);
                }
                if (witchPoisonTarget != null && !dead.contains(witchPoisonTarget)) {
                    dead.add(witchPoisonTarget);
                    if ("猎人".equals(session.playerRole(witchPoisonTarget))) hunterCanShoot = false;
                }
                pendingAnnouncement = "🔍 预言家请闭眼。\n\n☀️ 天亮了！";
                wolfKillTarget = null; witchPoisonTarget = null;
                antidoteUsedThisRound = false;
            }
            // 天亮
            if (line.contains("进入白天") || line.contains("天亮了")) {
                night = false;
                if (nightPhase != NightPhase.DONE) {
                    // AI直接跳过了阶段结束标签，强制执行死亡结算
                    if (antidoteUsedThisRound && wolfKillTarget != null) {
                        dead.remove(wolfKillTarget);
                    }
                    if (witchPoisonTarget != null && !dead.contains(witchPoisonTarget)) {
                        dead.add(witchPoisonTarget);
                        if ("猎人".equals(session.playerRole(witchPoisonTarget))) hunterCanShoot = false;
                    }
                    wolfKillTarget = null; witchPoisonTarget = null;
                    antidoteUsedThisRound = false;
                    nightPhase = NightPhase.DONE;
                }
            }
            // 天黑（进入下一夜）
            if (line.contains("进入黑夜") || line.contains("天黑了")) {
                night = true; round++;
                nightPhase = NightPhase.WOLVES;
                antidoteUsedThisRound = false;
                wolfKillTarget = null; witchPoisonTarget = null;
                wolfProposal = null; wolfProposer = null; wolfAgreed.clear();
                pendingAnnouncement = "🌙 天黑请闭眼。\n🐺 狼人请睁眼。";
            }
            if (line.contains("游戏结束")) over = true;
        }
        return pendingAnnouncement;
    }

    @Override public boolean isNight() { return night; }
    @Override public boolean isOver() { return over; }
    @Override public boolean isPlayerAlive(String playerName) { return !dead.contains(playerName); }

    // ==================== 白天阶段（系统驱动） ====================

    //public enum DayPhase { NONE, SHERIFF_VOTE, DISCUSS, EXILE_VOTE, DONE }
    public enum DayPhase { NONE, EXILE_VOTE, DONE } // 简化：去掉上警和讨论

    private DayPhase dayPhase = DayPhase.NONE;
    private final Map<String, String> activeVotes = new LinkedHashMap<>();
    private java.util.List<String> voteOrder; // 投票顺序
    private int currentVoterIdx;               // 当前轮到第几个

    public DayPhase getDayPhase() { return dayPhase; }
    public boolean isInVotePhase() { return dayPhase == DayPhase.EXILE_VOTE; }

    /** 天亮后逐个叫名投票 */
    public String beginDaytime() {
        dayPhase = DayPhase.EXILE_VOTE;
        activeVotes.clear();
        GameSession gs = GameRegistry.session();
        voteOrder = new java.util.ArrayList<>();
        if (gs != null) for (String n : gs.playerNames()) {
            if (isPlayerAlive(n)) voteOrder.add(n);
        }
        currentVoterIdx = 0;
        if (voteOrder.isEmpty()) return "☀️ 天亮了！没有存活玩家。";
        return "☀️ 天亮了！开始放逐投票。\n🗳 " + voteOrder.get(0) + " 请投票，说出你要放逐的玩家名。";
    }

    /** 当前轮到谁投票 */
    public String currentVoterName() {
        if (voteOrder == null || currentVoterIdx >= voteOrder.size()) return null;
        return voteOrder.get(currentVoterIdx);
    }

    /** 推进到下一个投票者，返回提示；null=全部投完 */
    public String nextVoterPrompt() {
        currentVoterIdx++;
        if (currentVoterIdx >= voteOrder.size()) return null;
        return "🗳 " + voteOrder.get(currentVoterIdx) + " 请投票，说出你要放逐的玩家名。";
    }

    /* 警长选出后→开始讨论
    public String startDiscuss() {
        dayPhase = DayPhase.DISCUSS;
        discussStartMs = System.currentTimeMillis();
        discussReminded = false;
        return "💬 自由讨论开始（3分钟），所有玩家可以发言。";
    }

    public String checkDiscussTimer() {
        if (dayPhase != DayPhase.DISCUSS) return null;
        long elapsed = (System.currentTimeMillis() - discussStartMs) / 1000;
        if (elapsed >= DISCUSS_SEC) {
            dayPhase = DayPhase.EXILE_VOTE;
            activeVotes.clear();
            return "⏰ 讨论时间到！现在开始放逐投票，请每位存活玩家输入你要投的玩家名。";
        }
        if (!discussReminded && elapsed >= DISCUSS_REMIND_SEC) {
            discussReminded = true;
            return "⏰ 讨论还剩30秒，请尽快发言。";
        }
        return null;
    }
    */

    /**
     * 处理投票。target 为投票对象，voter 为投票人。
     * @return null=已记录等下一个，以❌开头=错误，以📊开头=全部投完的结果
     */
    public String handleVote(String voter, String target, GameSession session) {
        if (!isInVotePhase()) return "❌ 当前不在投票阶段。";
        // 检查是否轮到该玩家
        String cur = currentVoterName();
        if (cur == null) return "❌ 投票已结束。";
        if (!cur.equals(voter)) return "❌ 还没轮到你，现在是 " + cur + " 在投票。";
        if (target == null || target.equals(voter)) return "❌ 不能投给自己。";
        if (!session.playerNames().contains(target)) return "❌ 没有这个玩家。";
        if (!isPlayerAlive(target)) return "❌ " + target + " 已经死了，请投给存活玩家。";
        activeVotes.put(voter, target);

        // 推进到下一个
        String next = nextVoterPrompt();
        if (next != null) return null; // 还有下一个，返回null让BotApp提示下一人
        // 全部投完
        return tally(session);
    }

    private String tally(GameSession session) {
        Map<String, Integer> cnt = new LinkedHashMap<>();
        for (String t : activeVotes.values()) cnt.merge(t, 1, Integer::sum);
        String winner = null;
        int max = 0;
        boolean tie = false;
        for (var e : cnt.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); winner = e.getKey(); tie = false; }
            else if (e.getValue() == max) { tie = true; }
        }
        StringBuilder sb = new StringBuilder("📊 投票结果：\n");
        for (var e : cnt.entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("票\n");

        if (tie) {
            // 平局不放逐，直接进下一夜
            sb.append("\n⚡ 票数相同，无人被放逐。");
            night = true; round++;
            nightPhase = NightPhase.WOLVES;
            antidoteUsedThisRound = false;
            wolfKillTarget = null; witchPoisonTarget = null;
            wolfProposal = null; wolfProposer = null; wolfAgreed.clear();
            sb.append("\n\n🌙 天黑请闭眼。\n🐺 狼人请睁眼。");
            dayPhase = DayPhase.NONE;
            return sb.toString();
        }
        /* 上警已取消
        if (dayPhase == DayPhase.SHERIFF_VOTE) {
            sheriff = winner;
            sb.append("\n🎖 ").append(winner).append(" 当选警长！（1.5票+最后发言）");
            return startDiscuss() + "\n\n" + sb.toString();
        } */
        {
            dead.add(winner);
            sb.append("\n🚫 ").append(winner).append(" 被放逐出局！");
            if ("猎人".equals(session.playerRole(winner)) && hunterCanShoot) {
                sb.append("\n🔫 ").append(winner).append(" 是猎人，可以开枪带走一人！（输入玩家名）");
            }
            long wolvesAlive = session.playerNames().stream()
                .filter(n -> !dead.contains(n) && "狼人".equals(session.playerRole(n))).count();
            long goodsAlive = session.playerNames().stream()
                .filter(n -> !dead.contains(n) && !"狼人".equals(session.playerRole(n))).count();
            if (wolvesAlive == 0) { over = true; sb.append("\n\n🎉 所有狼人被放逐，好人阵营获胜！"); }
            else if (wolvesAlive >= goodsAlive) { over = true; sb.append("\n\n🐺 狼人数量占优，狼人阵营获胜！"); }
            else {
                // 进入下一夜
                night = true; round++;
                nightPhase = NightPhase.WOLVES;
                antidoteUsedThisRound = false;
                wolfKillTarget = null; witchPoisonTarget = null;
                wolfProposal = null; wolfProposer = null; wolfAgreed.clear();
                sb.append("\n\n🌙 天黑请闭眼。\n🐺 狼人请睁眼。");
            }
        }
        if (!over) dayPhase = DayPhase.NONE;
        else dayPhase = DayPhase.DONE;
        return sb.toString();
    }

    private static String extractCmd(String line, String prefix) {
        int s = line.indexOf(prefix) + prefix.length();
        int e = line.indexOf("】", s);
        if (s < prefix.length() || e < 0) return null;
        String v = line.substring(s, e).strip();
        return v.isEmpty() ? null : v;
    }
}
