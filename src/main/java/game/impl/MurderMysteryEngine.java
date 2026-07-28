package game.impl;

import game.GameEngine;
import game.GameRegistry;
import game.GameSession;

import java.util.*;

/**
 * AI 剧本杀引擎 — 支持 4~9 人。
 *
 * <p>每次游戏 AI 当场生成唯一案件和角色。使用 {@code 【私信:玩家名】}
 * 格式私发角色卡和线索，公开讨论自动广播至所有玩家。
 */
public class MurderMysteryEngine implements GameEngine {

    enum Phase { BRIEFING, INVESTIGATE, VOTE, REVEAL }

    private Phase phase = Phase.BRIEFING;
    private final Map<String, Integer> searchCounts = new HashMap<>();
    private final Map<String, String> votes = new LinkedHashMap<>();
    private int voteRound;
    private boolean over;

    @Override public String name() { return "剧本杀"; }
    @Override public int minPlayers() { return 4; }
    @Override public int maxPlayers() { return 9; }

    // ==================== 规则 Prompt ====================

    @Override
    public String systemPrompt() {
        GameSession gs = GameRegistry.session();
        int total = gs != null ? gs.playerNames().size() : minPlayers();
        StringBuilder names = new StringBuilder();
        if (gs != null) for (String n : gs.playerNames()) names.append(n).append("、");
        return """
            你是一个专业的剧本杀主持人（DM）。参与玩家共 %d 人：%s。

            === 游戏流程 ===
            1. 开局 → 立即公开宣布案件背景（死者/死因/时间/地点/关键证物）
            2. 立即用【私信:玩家名】给每位玩家发详细角色卡
            3. 进入「🔍 调查阶段」→ 玩家自由讨论+搜证
            4. 有人指认 → 全体投票 → 揭示真相

            === 开局时的输出格式（严格按此顺序执行）===
            第一步：一段公开文字宣布案件背景。直接说话不要加前缀。
            第二步：依次发送【私信:玩家名】角色卡给每位玩家。每个玩家都必须收到！

            角色卡模板（必须包含以下所有内容）：
            【私信:张三】
            🎭 你的身份：XXX（职业）
            📋 背景：你与死者的关系，案发时你在哪里做什么
            🔑 你知道的秘密：只有你知道的关键信息
            ⚠️ 你不能暴露的事：你必须隐藏的秘密，一旦暴露你会失去什么
            💡 你怀疑谁：你怀疑的人及理由

            必须是完整段落描述，不能只写职业名！

            === 调查阶段 ===
            玩家可以公开讨论（直接说话即可，会自动广播给全体）
            玩家说「我要搜证」→ 根据其角色给一条专属线索（每人限3次）
            过半玩家同意指认某人 → 组织投票
            所有人投票后 → 公布真相+评分

            === 投票阶段 ===
            逐一收集每位玩家的投票，投票完毕立即进入揭示阶段

            === 揭示阶段 ===
            公布真凶、动机、手法；公布每位玩家的真实身份和秘密；给每位玩家打分+简短评价

            === 格式规则（致命重要）===
            - 【私信:玩家名】中玩家名只用纯昵称！正确：【私信:张三】，错误：【私信:张三(医生)】
            - 角色卡内容要详细，至少100字
            - 开局后必须立即执行第一步和第二步，不能等待或延迟""".formatted(total, names.toString());
    }

    // ==================== 状态快照 ====================

    @Override
    public String stateContext() {
        GameSession gs = GameRegistry.session();
        if (gs == null) return "";
        StringBuilder s = new StringBuilder();
        s.append("【当前阶段】");
        s.append(switch (phase) {
            case BRIEFING -> "分发角色卡中";
            case INVESTIGATE -> "自由调查讨论中";
            case VOTE -> "投票指认（第" + voteRound + "轮）";
            case REVEAL -> "揭示真相";
        });
        s.append("\n搜证次数：");
        for (String n : gs.playerNames()) {
            s.append(n).append("(").append(searchCounts.getOrDefault(n, 0)).append("/3) ");
        }
        if (phase == Phase.VOTE && !votes.isEmpty()) {
            s.append("\n已投票：");
            for (var e : votes.entrySet()) s.append(e.getKey()).append("→").append(e.getValue()).append(" ");
        }
        return s.toString();
    }

    // ==================== 开始游戏 ====================

    @Override
    public String start(GameSession session) {
        for (String n : session.playerNames()) searchCounts.put(n, 0);
        phase = Phase.BRIEFING;

        StringBuilder playerList = new StringBuilder();
        for (String n : session.playerNames()) playerList.append("「").append(n).append("」");

        return """
            你是一个剧本杀主持人。现在有%d位玩家：%s

            请设计并宣布一个完整的谋杀谜案。只需要做以下两件事：

            ① 公开宣布案件背景
            用一段话描述：死者身份、死因、死亡时间地点、现场关键证物。
            不要加前缀标记，直接描述，要有悬疑感。

            ② 用【私信:玩家名】格式给每位玩家发详细角色卡
            必须给以上%d位玩家每一位都发！格式如下：

            【私信:玩家名】
            🎭 身份：职业 + 名字
            📋 背景：你与死者的关系，案发时你在哪里做什么
            🔑 秘密：只有你知道的关键信息
            ⚠️ 不可暴露：你必须隐藏的事及暴露后果
            💡 怀疑：你怀疑谁及理由

            每个角色卡至少150字详细描述。设定其中一人为真凶，只有TA自己知道。
            发完角色卡后宣布：🔍 调查阶段开始！自由讨论，说「我要搜证」获取线索（每人3次）。
            """.formatted(session.playerNames().size(), playerList.toString(),
                         session.playerNames().size());
    }

    /** 为指定玩家补发角色卡（当 AI 遗漏某些玩家时调用） */
    public String roleCardPrompt(String[] names, String caseContext) {
        StringBuilder list = new StringBuilder();
        for (String n : names) list.append("「").append(n).append("」");
        return """
            案件背景：
            %s

            以下玩家还没有收到角色卡，请立即为他们补发：%s

            必须用【私信:玩家名】格式，每人一个完整的角色卡，包含：
            🎭 身份 | 📋 背景 | 🔑 秘密 | ⚠️ 不可暴露 | 💡 怀疑对象
            每人至少150字。其中一人可能是真凶（如果真凶还未分配）。
            """.formatted(caseContext, list.toString());
    }

    // ==================== 处理发言 ====================

    @Override
    public String handle(GameSession session, String userId, String text) {
        String name = session.playerName(userId);
        if (name == null) return null;

        // 搜证
        if (text.contains("搜证") || text.contains("调查线索")) {
            int count = searchCounts.getOrDefault(name, 0);
            if (count >= 3) return "🔒 " + name + " 已用完3次搜证机会，请靠推理找出真凶。";
            searchCounts.put(name, count + 1);
            if (phase == Phase.BRIEFING) phase = Phase.INVESTIGATE;

            // 让 AI 生成该角色的专属线索，私密发给搜证者
            String searchPrompt = name + " 进行了第" + (count + 1) + "次搜证（共3次）。"
                + "请根据" + name + "的角色身份，给出1条专属线索。"
                + "线索应该能帮助TA推理但不直接暴露真凶。"
                + "用【私信:" + name + "】格式只发给TA一个人。";
            try {
                String clue = session.prompt(searchPrompt);
                System.out.println("[游戏:搜证] " + name + " 第" + (count + 1) + "次 → AI回复 "
                    + (clue != null ? clue.length() : 0) + "字符");
                return clue != null ? clue : "🔍 线索生成中，请稍后再试。";
            } catch (Exception e) {
                System.err.println("[游戏:搜证] ❌ " + e.getMessage());
                return "🔍 " + name + " 搜证第" + (count + 1) + "次。请 DM 根据" + name + "的角色给出专属线索。";
            }
        }

        // 指认/投票
        if (text.contains("指认") || text.contains("我投票") || text.contains("凶手是")) {
            if (phase == Phase.VOTE) {
                String target = extractTarget(text, session);
                if (target != null && !target.equals(name)) {
                    votes.put(name, target);
                    if (votes.size() >= session.playerNames().size()) {
                        phase = Phase.REVEAL;
                        over = true;
                        // 让 AI 揭晓真相
                        StringBuilder voteSummary = new StringBuilder();
                        for (var e : votes.entrySet())
                            voteSummary.append(e.getKey()).append("→").append(e.getValue()).append(" ");
                        String revealPrompt = "所有人已投票：" + voteSummary.toString().strip()
                            + "。请公布真相！包括：\n"
                            + "1. 真凶是谁、杀人动机、作案手法\n"
                            + "2. 每位玩家的真实身份和隐藏的秘密\n"
                            + "3. 给每位玩家简短打分+评价\n"
                            + "用公开文字宣布，涉及具体玩家的秘密可用【私信:玩家名】补充。";
                        try {
                            String reveal = session.prompt(revealPrompt);
                            System.out.println("[游戏:揭示] AI回复 " + (reveal != null ? reveal.length() : 0) + "字符");
                            return reveal != null ? reveal : "🎯 投票结束！真相已大白。";
                        } catch (Exception e) {
                            System.err.println("[游戏:揭示] ❌ " + e.getMessage());
                            return "🎯 所有人已投票！游戏结束，真相只有一个...";
                        }
                    }
                    return "✅ " + name + " 投 " + target + "，剩余 " + (session.playerNames().size() - votes.size()) + " 人待投。";
                }
                return null;
            }
            phase = Phase.VOTE;
            voteRound = 1;
            votes.clear();
            String target = extractTarget(text, session);
            if (target != null) votes.put(name, target);
            return "🗳 " + name + " 发起指认！进入投票阶段——请每位玩家说出你怀疑的凶手名字。当前 1/" + session.playerNames().size() + " 人已投票。";
        }

        if (phase == Phase.BRIEFING) phase = Phase.INVESTIGATE;
        return null;
    }

    private String extractTarget(String text, GameSession session) {
        for (String n : session.playerNames()) if (text.contains(n)) return n;
        return null;
    }

    @Override public boolean isOver() { return over; }
}
