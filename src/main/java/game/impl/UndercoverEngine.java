package game.impl;

import game.GameEngine;
import game.GameSession;

import java.util.*;

/**
 * 谁是卧底 — 完整官方规则引擎。
 *
 * <p>完全按照标准规则实现：发词（玩家不知身份）→ 固定顺序轮流描述 →
 * 统一投票（含平票重投）→ 公布出局者身份 → 胜负判定 → 循环。
 *
 * <p>所有游戏逻辑由 DeepSeek AI 主持执行，引擎只负责词分配和规则注入。
 */
public class UndercoverEngine implements GameEngine {

    /* ───── 词库：15 对高度相似词 ───── */
    private static final String[][] WORD_PAIRS = {
            {"苹果", "梨"},     {"牛奶", "豆浆"},   {"跑步", "散步"},
            {"沙发", "椅子"},   {"鼠标", "键盘"},   {"蛋糕", "面包"},
            {"钢笔", "铅笔"},   {"眼镜", "墨镜"},   {"西瓜", "冬瓜"},
            {"地铁", "公交"},   {"可乐", "雪碧"},   {"口红", "唇膏"},
            {"猫", "狐狸"},     {"奶茶", "果汁"},   {"信封", "邮票"}
    };

    private boolean over;
    private int undercoverCount;
    private final Map<String, String> wordByPlayer = new HashMap<>();
    private final Map<String, String> roleByPlayer = new HashMap<>();
    private String civilianWord;
    private String undercoverWord;

    /* ═══════════════════ 基础信息 ═══════════════════ */

    @Override public String name() { return "谁是卧底"; }
    @Override public int minPlayers() { return 4; }
    @Override public int maxPlayers() { return 12; }

    /* ═══════════════════ 续局状态 ═══════════════════ */

    private final java.util.Set<String> dead = new java.util.HashSet<>();

    @Override public boolean isOver() { return over; }
    @Override public boolean isPlayerAlive(String playerName) { return !dead.contains(playerName); }

    @Override public String handle(GameSession s, String uid, String text) {
        // text != null 意味着这是 AI 的回复，从中解析淘汰和胜负信息
        if (text != null) {
            parseAiAnnouncement(text, s);
        }
        return null; // 全权交给框架的 process()
    }

    /** 从 AI 回复中解析淘汰通知，更新存活状态 */
    private void parseAiAnnouncement(String text, GameSession s) {
        // 检测胜负判定 — 只在 AI 主持人语境下生效，避免玩家发言误触终局
        boolean hostContext = text.startsWith("[主持人]") || text.startsWith("主持人")
            || text.contains("获胜") || text.contains("淘汰") || text.contains("出局")
            || text.contains("本轮") || text.contains("投票");
        if (hostContext && (text.contains("平民获胜") || text.contains("卧底获胜"))) {
            over = true;
            return;
        }
        // 检测淘汰：AI 主持人公布「xxx被淘汰」等明确句式。
        // 收紧匹配，避免玩家自己的发言（含"淘汰/出局"字眼）被误判为淘汰公告。
        for (String name : s.playerNames()) {
            if (dead.contains(name)) continue;
            boolean announced = text.contains(name + "被淘汰") || text.contains(name + " 被淘汰")
                || text.contains(name + "被投出") || text.contains(name + " 被投出")
                || text.contains("淘汰了" + name) || text.contains("淘汰" + name + "出局")
                || text.contains("投出" + name) || text.contains(name + "出局")
                // 单独说「xx淘汰」且带主持人前缀时也认
                || ((text.startsWith("[主持人]") || text.startsWith("主持人"))
                    && (text.contains("淘汰" + name) || text.contains(name + "淘汰")));
            if (announced) {
                dead.add(name);
                // 更新卧底计数
                if ("卧底".equals(s.playerRole(name))) undercoverCount--;
                System.out.println("[卧底] " + name + " 被淘汰（" + undercoverCount + "卧底剩余）");
            }
        }
    }

    /* ═══════════════════ 系统提示（AI 主持人角色） ═══════════════════ */

    @Override
    public String systemPrompt() {
        return """
你是「谁是卧底」的专职主持人。你已掌握所有玩家的词和身份。
严格按以下流程执行，不可跳过或改变顺序。

【身份规则】
- 平民词相同，卧底词不��但相似。
- 每个玩家只知道自己的词，不知道自己是平民还是卧底。
- 禁止向任何玩家透露其身份或其他人的词。

【每轮流程 · 严格按顺序】
第1步·轮流描述：按玩家列表顺序，每次只邀请一位玩家。
   用「请【xxx】描述」点名。其他人插话时回复「还没轮到你」。
   该玩家描述完后，你回复「xxx描述完毕，下一位请【yyy】描述」。
   违规发言立即制止：说出词的扣分、复读的警告、暴露专属特征的直接作废。
第2步·统一投票：全部描述完后宣布「现在开始投票，每人发送『投票 xxx』」。
   收到所有投票后，统计票数。
第3步·公布结果：公布最高得票者及其真实身份（平民/卧底）。
   平票规则：两人以上票数相同→平票者各再说一句话→其余人针对平票者二次投票。
第4步·胜负判定：
   → 所有卧底被投票淘汰 → 「🎉 平民获胜！」
   → 卧底人数 ≥ 平民存活人数 → 「🕵 卧底获胜！」
   → 否则 → 回到第1步，继续下一轮。
   → 如只剩1卧底+1平民，卧底存活→卧底胜。

【玩家发言硬性规则（违规立即警告）】
1. 只能一句话描述，不能分多句
2. 绝对不能说出词语本身或其中任何一个字
3. 不能说字数、偏旁部首、拼音、英文
4. 不能说绝对专属特征（如猫=会喵喵叫）
5. 不能复读上一个人原话
6. 禁止一切暗示和场外提示

【你的发言规范】
- 公共发言不加前缀或使用「[主持人]」前缀
- 私信给个人时使用「【私信:玩家名】内容」格式
- 语气正式但不死板，像真人主持人
""";
    }

    /* ═══════════════════ 开局：分词 ═══════════════════ */

    @Override
    public String start(GameSession session) {
        wordByPlayer.clear();
        roleByPlayer.clear();
        dead.clear();
        over = false;

        /* ── 1. 抽词 ── */
        Random rand = new Random();
        String[] pair = WORD_PAIRS[rand.nextInt(WORD_PAIRS.length)];
        civilianWord = pair[0];
        undercoverWord = pair[1];

        /* ── 2. 确定卧底数量 ── */
        int total = session.playerNames().size();
        if (total <= 6) undercoverCount = 1;
        else if (total <= 10) undercoverCount = 2;
        else undercoverCount = 3;

        /* ── 3. 随机分配身份和词 ── */
        List<String> players = new ArrayList<>(session.playerNames());
        Collections.shuffle(players, rand);

        int i = 0;
        for (String name : players) {
            if (i < undercoverCount) {
                wordByPlayer.put(name, undercoverWord);
                roleByPlayer.put(name, "卧底");
                session.setRole(name, "卧底");
            } else {
                wordByPlayer.put(name, civilianWord);
                roleByPlayer.put(name, "平民");
                session.setRole(name, "平民");
            }
            i++;
        }

        over = false;

        /* ── 4. 组装 AI 指令 ──
           注意：框架的 session.prompt() 会先注入 systemPrompt，
           所以这里的文本只须包含数据和输出格式指令  */
        StringBuilder sb = new StringBuilder();

        // 内部数据（AI 参考用）
        sb.append("以下为本局数据（仅供你参考，严禁告诉任何玩家）：\n");
        sb.append("玩家总数 ").append(total).append("，卧底 ").append(undercoverCount).append(" 人\n");
        sb.append("平民词「").append(civilianWord).append("」　卧底词「").append(undercoverWord).append("」\n");
        for (String name : players) {
            sb.append("  ").append(name).append("：").append(roleByPlayer.get(name)).append("\n");
        }

        // ★ 关键：框架解析规则——【私信:name】之前的内容是公告（广播全员），
        //    【私信:name】开头的行发给指定玩家。所以公告必须在私信之前输出。

        // ① 公开公告（在【私信:name】之前，会被广播给所有人）
        sb.append("\n首先公开宣布游戏开始：\n");
        sb.append("共 ").append(total).append(" 名玩家，其中隐藏 ").append(undercoverCount).append(" 名卧底。\n");
        sb.append("发言顺序：");
        for (int idx = 0; idx < players.size(); idx++) {
            sb.append(players.get(idx));
            if (idx < players.size() - 1) sb.append(" → ");
        }
        sb.append("。\n请【").append(players.get(0)).append("】先描述，注意不能说出词中的字。\n");
        sb.append("规则重申：一句话描述、不能说出词本身、不能说偏旁拼音、不能复读。\n");

        // ② 私信发词（用【私信:name】格式，框架会自动分发给对应玩家）
        sb.append("\n然后为每位玩家私信发词（只发词，绝不透露身份）：\n");
        for (String name : players) {
            sb.append("【私信:").append(name).append("】你的词是：").append(wordByPlayer.get(name)).append("\n");
        }

        return sb.toString();
    }

    /* ═══════════════════ 游戏中状态注入 ═══════════════════ */

    @Override
    public String stateContext() {
        if (wordByPlayer.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【主持人内部数据——严禁以任何形式透露给玩家】\n");

        // 存活玩家统计
        long aliveCount = roleByPlayer.keySet().stream().filter(n -> !dead.contains(n)).count();
        sb.append("当前存活 ").append(aliveCount).append(" 人（卧底 ").append(undercoverCount).append(" 人）：\n");
        for (var e : wordByPlayer.entrySet()) {
            if (dead.contains(e.getKey())) continue; // 跳过已淘汰
            String r = roleByPlayer.get(e.getKey());
            sb.append("  ").append(e.getKey()).append("：词「").append(e.getValue()).append("」身份").append(r).append("\n");
        }

        // 胜负判断公式（只统计存活）
        int aliveCiv = (int) roleByPlayer.entrySet().stream()
            .filter(e -> !dead.contains(e.getKey()) && "平民".equals(e.getValue())).count();
        int aliveUc = (int) roleByPlayer.entrySet().stream()
            .filter(e -> !dead.contains(e.getKey()) && "卧底".equals(e.getValue())).count();
        sb.append("平民存活 ").append(aliveCiv).append(" 人，卧底存活 ").append(aliveUc).append(" 人\n");
        if (aliveUc == 0) sb.append("→ 平民获胜\n");
        else if (aliveUc >= aliveCiv) sb.append("→ 卧底获胜\n");
        else sb.append("→ 游戏继续\n");

        return sb.toString();
    }

    /* ═══════════════════ 公开方法 ═══════════════════ */

    /** 查询玩家词（调试用） */
    public String getWord(String playerName) {
        return wordByPlayer.get(playerName);
    }
}
