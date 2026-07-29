package game.impl;

import game.GameEngine;
import game.GameSession;

import java.util.*;

public class UndercoverEngine implements GameEngine {
    // ============ 词库：平民词在前，卧底词在后 ============
    private static final String[][] WORD_PAIRS = {
            {"苹果", "梨"},
            {"牛奶", "豆浆"},
            {"跑步", "散步"},
            {"沙发", "椅子"},
            {"鼠标", "键盘"}
    };

    private boolean over;
    private int undercoverCount;
    private final Map<String, String> wordByPlayer = new HashMap<>();

    @Override
    public String name() { return "谁是卧底"; }

    @Override
    public int minPlayers() { return 4; }

    @Override
    public int maxPlayers() { return 10; }

    @Override
    public String systemPrompt() {
        return """
        你是「谁是卧底」的主持人。你严格按流程主持，不允许跳过任何步骤。

        当前游戏有 N 个玩家，其中 %d 个是卧底，其余是平民。
        每人拿到一个词。平民词相同，卧底词不同但相似。
        玩家角色已分配完毕，名单会在每条消息中给出。

        === 严格流程（务必遵守） ===
        第1步 - 开场：宣布游戏开始，说明总人数和卧底人数。
        第2步 - 按顺序描述：按[当前玩家]列表顺序，一次只邀请一位玩家发言。
                 每次只@一位玩家："请【xxx】描述你的词语"。
                 该玩家描述后，你回应"xxx描述完毕，下一位请【yyy】描述"。
                 依次类推，直到所有玩家都描述完。
        第3步 - 投票：所有玩家描述完后，立即宣布开始投票。
                 每位玩家发送"投票 xxx"来投票。
        第4步 - 结果：统计票数，宣布被投出者及其身份。
        第5步 - 循环或结束：若游戏继续，回到第2步；否则宣布游戏结束。

        === 核心规则 ===
        - 每次只邀请一位玩家。其他人发消息时回复"还没轮到你，请等待【xxx】发言"。
        - 描述不能出现词语中的任何一个字。
        - 禁止透露任何玩家的词语或身份（除非被投出公布身份）。
        - 用 [主持人] 前缀发公告。
        """.formatted(undercoverCount);
    }

    @Override
    public String start(GameSession session) {
        wordByPlayer.clear(); // 清空上一局旧数据

        Random rand = new Random();
        String[] pair = WORD_PAIRS[rand.nextInt(WORD_PAIRS.length)];
        String civilianWord = pair[0];
        String undercoverWord = pair[1];

        int total = session.playerNames().size();
        undercoverCount = Math.min(3, Math.max(1, total / 4));

        List<String> players = new ArrayList<>(session.playerNames());
        Collections.shuffle(players);

        int i = 0;
        for (String name : players) {
            if (i < undercoverCount) {
                wordByPlayer.put(name, undercoverWord);
                session.setRole(name, "卧底");
            } else {
                wordByPlayer.put(name, civilianWord);
                session.setRole(name, "平民");
            }
            i++;
        }

        over = false;

        return "🎭 游戏开始！" + total + "位玩家中有" + undercoverCount + "个卧底。"
                + "\n角色已分配，请查看你的身份。"
                + "\n📢 每人轮流描述自己的词，不能说出词中的字！";
    }

    @Override
    public String handle(GameSession session, String userId, String text) {
        return null;  // 多人游戏，交给 GameSession 统一处理
    }

    @Override
    public boolean isOver() { return over; }

    /** 获取某个玩家的词语 */
    public String getWord(String playerName) {
        return wordByPlayer.get(playerName);
    }
}
