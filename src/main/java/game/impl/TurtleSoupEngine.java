package game.impl;

import game.GameEngine;
import game.GameSession;

import java.util.*;

/**
 * 海龟汤引擎 — 单人推理游戏，玩家通过提问猜测真相。
 */
public class TurtleSoupEngine implements GameEngine {

    private boolean over;
    private String soup; // 当前汤面（谜题）
    private String answer; // 汤底（真相）
    private final List<String> questions = new ArrayList<>();
    private int questionCount;

    // 内置汤面库：汤面 → 汤底
    private static final Map<String, String> SOUP_LIBRARY = new LinkedHashMap<>();
    static {
        SOUP_LIBRARY.put(
            "一个男人走进餐厅，点了一碗海龟汤。他尝了一口，随即冲出去自杀了。为什么？",
            "这个男人曾经在船上遭遇过海难，船员们快要饿死时，船长给大家分发\"海龟汤\"。实际上那是死去船员的肉。他当时不知情，现在尝到真正的海龟汤，发现味道完全不同，才明白当年吃的是人肉，无法接受而自杀。"
        );
        SOUP_LIBRARY.put(
            "一个女人每天早上都会收到一束玫瑰花，持续了十年。第十一年，玫瑰停了。为什么？",
            "十年前，这个女人救了一个男人的命，男人承诺会每年送她玫瑰表达感谢。男人去世前交代家人继续送，但十一年后家人也忘记了这件事。"
        );
        SOUP_LIBRARY.put(
            "小明住在12楼，每天上班会坐电梯到1楼。下雨天或有其他人时，他直接坐到1楼。否则他会坐到6楼再走下去。为什么？",
            "小明是个侏儒，身高只能够到6楼的按钮。下雨天他可以用伞按1楼，有其他人时别人会帮他按1楼。否则他只能按到6楼然后走下去。"
        );
        SOUP_LIBRARY.put(
            "一个医生走进酒吧，点了一杯水。酒吧老板拿出一把枪指着他。医生说了一句谢谢，然后离开了。为什么？",
            "医生之前帮过酒吧老板一个大忙（比如救治过他的家人），老板一直想报答他。医生这次来是为了确认老板是否还经营这家酒吧，看到老板拿出枪（其实是个玩笑或误会），医生明白老板的意思后道谢离开。"
        );
        SOUP_LIBRARY.put(
            "一个人在沙漠中发现了一具尸体，尸体旁边有一个小袋子。如果袋子里有水，他就不会死。为什么？",
            "这个人是在沙漠中迷路后，发现了一具前人的尸体和一个空的水袋。他知道自己也面临同样的命运，如果水袋里还有水，说明之前的人喝过水后还是死了，水可能被污染或有毒，那他就不会继续使用这水。"
        );
    }

    @Override
    public String name() { return "海龟汤"; }

    @Override
    public int minPlayers() { return 1; }

    @Override
    public int maxPlayers() { return 1; }

    @Override
    public String systemPrompt() {
        return """
            你是海龟汤游戏主持人。规则如下：

            【玩法】
            - 我会给出一个"汤面"（谜题场景）
            - 玩家通过提问来猜测真相
            - 你只能回答三种：是 / 不是 / 无关

            【回答规则】
            - 只能用"是"、"不是"、"无关"、"不完全是"来回答
            - 不透露任何额外线索
            - 如果玩家问到关键问题，可以用"是"来引导

            【汤面】
            %s

            【汤底】
            %s

            【判定】
            - 当玩家猜出核心真相（汤底）时，宣布"🎉 恭喜你破案了！"并完整复述汤底
            - 如果玩家长时间无法猜出，可以给出提示

            现在开始游戏，请向玩家展示汤面，等待他的第一个问题。
            """.formatted(soup, answer);
    }

    @Override
    public String start(GameSession session) {
        over = false;
        questionCount = 0;
        questions.clear();

        List<String> keys = new ArrayList<>(SOUP_LIBRARY.keySet());
        soup = keys.get(new Random().nextInt(keys.size()));
        answer = SOUP_LIBRARY.get(soup);

        System.out.println("[海龟汤] 新汤面: " + soup);
        System.out.println("[海龟汤] 汤底: " + answer);

        return systemPrompt() + "\n\n请以海龟汤游戏主持人的身份，向玩家介绍汤面，并邀请玩家开始提问。";
    }

    @Override
    public String handle(GameSession session, String userId, String text) {
        // 退出游戏
        if (isQuitCommand(text)) {
            over = true;
            return "🚪 已退出海龟汤游戏。共提问 " + questionCount + " 次。";
        }

        questionCount++;
        questions.add(text);

        String reply = session.process(userId, text);

        if (reply != null && reply.contains("🎉 恭喜你破案了")) {
            over = true;
            System.out.println("[海龟汤] 玩家破案！提问次数：" + questionCount);
        }

        return reply;
    }

    private boolean isQuitCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.equalsIgnoreCase("结束游戏") || t.equalsIgnoreCase("退出游戏")
            || t.equalsIgnoreCase("不玩了") || t.equalsIgnoreCase("退出")
            || t.equalsIgnoreCase("结束") || t.equalsIgnoreCase("quit");
    }

    @Override
    public boolean isOver() {
        return over;
    }

    public int getQuestionCount() {
        return questionCount;
    }
}
