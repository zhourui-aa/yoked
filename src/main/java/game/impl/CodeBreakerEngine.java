package game.impl;

import game.GameEngine;
import game.GameSession;

import java.util.*;

/**
 * 密码破译引擎 — 经典猜数字游戏（Bulls and Cows）。
 *
 * <p>AI 生成 4 位不重复数字，玩家通过猜测推理破解密码。
 * 反馈格式：xAyB（A=数字和位置都正确，B=数字正确但位置错误）。
 * 纯本地逻辑，不调用 AI。
 */
public class CodeBreakerEngine implements GameEngine {

    private static final int CODE_LENGTH = 4;
    private static final int MAX_ATTEMPTS = 10;

    private String code;
    private int attempts;
    private boolean over;
    private boolean won;
    private final List<GuessRecord> history = new ArrayList<>();

    private static class GuessRecord {
        final String guess;
        final int a; // 位置+数字都对
        final int b; // 数字对但位置错
        final int attempt;

        GuessRecord(String guess, int a, int b, int attempt) {
            this.guess = guess;
            this.a = a;
            this.b = b;
            this.attempt = attempt;
        }
    }

    @Override public String name() { return "密码破译"; }
    @Override public int minPlayers() { return 1; }
    @Override public int maxPlayers() { return 1; }

    @Override
    public String systemPrompt() {
        return """
            你是密码破译游戏主持人。规则：
            - 密码是 4 位不重复数字（0-9）
            - 玩家输入 4 位数字猜测
            - 反馈格式 xAyB：A=数字和位置都正确，B=数字正确但位置错误
            - 最多 10 次猜测机会
            - 猜中 4A 获胜
            """;
    }

    @Override
    public String start(GameSession session) {
        over = false;
        won = false;
        attempts = 0;
        history.clear();
        code = generateCode();

        System.out.println("[密码破译] 新密码: " + code);

        return welcomeMessage();
    }

    /** 欢迎消息（供 GameCommand.startSinglePlayerGame 直接使用，不经过 AI） */
    public String welcomeMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("🔐 密码破译\n\n");
        sb.append("AI 已生成一个 4 位不重复数字的密码（0-9）。\n");
        sb.append("你有 ").append(MAX_ATTEMPTS).append(" 次猜测机会。\n");
        sb.append("反馈格式：xAyB\n");
        sb.append("  A = 数字和位置都正确\n");
        sb.append("  B = 数字正确但位置错误\n\n");
        sb.append("💡 输入 4 位数字开始猜测，如：1234\n");
        sb.append("📜 输入「历史」查看记录，🔍 输入「提示」获取线索，🚪 输入「退出」结束游戏");
        return sb.toString();
    }

    @Override
    public String handle(GameSession session, String userId, String text) {
        if (over) {
            return "游戏已结束。输入「密码破译」重新开始。";
        }

        text = text == null ? "" : text.trim();

        // 命令处理
        if (isCommand(text)) {
            return handleCommand(text);
        }

        // 输入校验
        String validation = validateGuess(text);
        if (validation != null) return validation;

        // 处理猜测
        attempts++;
        int[] result = calculateResult(text, code);
        int a = result[0], b = result[1];

        history.add(new GuessRecord(text, a, b, attempts));

        StringBuilder sb = new StringBuilder();
        sb.append("📏 第 ").append(attempts).append("/").append(MAX_ATTEMPTS).append(" 次猜测\n");
        sb.append("   你的猜测：").append(text).append("\n");
        sb.append("   结果：").append(a).append("A").append(b).append("B\n\n");

        // 胜利判定
        if (a == CODE_LENGTH) {
            won = true;
            over = true;
            sb.append("🎉 恭喜！你在第 ").append(attempts).append(" 次破解了密码！\n\n");
            sb.append("密码是：").append(code).append("\n\n");
            sb.append(generateSummary());
            return sb.toString();
        }

        // 失败判定
        if (attempts >= MAX_ATTEMPTS) {
            over = true;
            sb.append("💀 机会用完了！密码是：").append(code).append("\n\n");
            sb.append(generateSummary());
            return sb.toString();
        }

        // 继续游戏
        int remaining = MAX_ATTEMPTS - attempts;
        sb.append("⏳ 剩余 ").append(remaining).append(" 次机会。\n");
        sb.append(smartHint(a, b, text));
        return sb.toString();
    }

    /** 生成 4 位不重复数字密码 */
    private String generateCode() {
        List<Integer> digits = new ArrayList<>();
        for (int i = 0; i < 10; i++) digits.add(i);
        Collections.shuffle(digits);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(digits.get(i));
        return sb.toString();
    }

    /** 校验猜测输入 */
    private String validateGuess(String guess) {
        if (guess.length() != CODE_LENGTH) {
            return "❌ 请输入恰好 " + CODE_LENGTH + " 位数字。";
        }
        if (!guess.matches("\\d{" + CODE_LENGTH + "}")) {
            return "❌ 只能输入数字 0-9。";
        }
        // 检查重复
        Set<Character> set = new HashSet<>();
        for (char c : guess.toCharArray()) set.add(c);
        if (set.size() < CODE_LENGTH) {
            return "❌ 密码中的数字不能重复，请输入 4 个不同的数字。";
        }
        return null;
    }

    /** 计算 xA yB */
    private int[] calculateResult(String guess, String answer) {
        int a = 0, b = 0;
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (guess.charAt(i) == answer.charAt(i)) {
                a++;
            } else if (answer.indexOf(guess.charAt(i)) >= 0) {
                b++;
            }
        }
        return new int[]{a, b};
    }

    /** 判断是否为命令 */
    private boolean isCommand(String text) {
        String t = text.toLowerCase();
        return t.equals("退出") || t.equals("结束") || t.equals("quit")
            || t.equals("退出游戏") || t.equals("结束游戏") || t.equals("不玩了")
            || t.equals("历史") || t.equals("history")
            || t.equals("提示") || t.equals("hint")
            || t.equals("放弃") || t.equals("认输")
            || t.equals("重开") || t.equals("重新开始") || t.equals("再来一局");
    }

    /** 处理命令 */
    private String handleCommand(String text) {
        String t = text.toLowerCase();

        if (t.equals("退出") || t.equals("结束") || t.equals("quit")
            || t.equals("退出游戏") || t.equals("结束游戏") || t.equals("不玩了")) {
            over = true;
            return "🚪 已退出游戏。密码是：" + code;
        }

        if (t.equals("放弃") || t.equals("认输")) {
            over = true;
            return "💔 你选择了放弃。密码是：" + code;
        }

        if (t.equals("重开") || t.equals("重新开始") || t.equals("再来一局")) {
            over = false;
            won = false;
            attempts = 0;
            history.clear();
            code = generateCode();
            return "🔄 新密码已生成！\n\n" + welcomeMessage();
        }

        if (t.equals("历史") || t.equals("history")) {
            if (history.isEmpty()) return "还没有猜测记录。";
            StringBuilder sb = new StringBuilder("📜 猜测历史：\n\n");
            for (GuessRecord r : history) {
                sb.append("  #").append(r.attempt).append(": ")
                  .append(r.guess).append(" → ")
                  .append(r.a).append("A").append(r.b).append("B\n");
            }
            return sb.toString();
        }

        if (t.equals("提示") || t.equals("hint")) {
            return generateHint();
        }

        return "";
    }

    /** 智能提示（根据已有猜测推算可能的密码范围） */
    private String generateHint() {
        if (history.isEmpty()) {
            return "💡 提示：试试从 0、1、2、3 开始。\n";
        }

        StringBuilder sb = new StringBuilder("💡 分析提示：\n");

        // 统计每位数字的命中情况
        int[] digitHits = new int[10]; // 每个数字出现的次数
        for (GuessRecord r : history) {
            for (char c : r.guess.toCharArray()) {
                digitHits[c - '0']++;
            }
        }

        // 找出从未被猜过的数字
        StringBuilder unguessed = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (digitHits[i] == 0) unguessed.append(i);
        }

        // 找出至少匹配过一次的数字（可能是密码的一部分）
        StringBuilder potential = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (digitHits[i] > 0) potential.append(i);
        }

        sb.append("  可能在密码中的数字：").append(potential).append("\n");
        if (unguessed.length() > 0) {
            sb.append("  从未猜过的数字：").append(unguessed).append("\n");
        }

        // 基于已有线索的推理建议
        sb.append("\n  推理思路：\n");
        sb.append("  - 注意每次的 A（位置正确）和 B（数字正确但位置错）\n");
        sb.append("  - 如果 B 很多，说明选对了数字但位置不对\n");
        sb.append("  - 如果 A 增加，说明你找到了正确的位置\n");

        return sb.toString();
    }

    /** 生成游戏总结 */
    private String generateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ 📊 游戏总结 ═══\n\n");
        sb.append("密码：").append(code).append("\n");
        sb.append("尝试次数：").append(attempts).append("/").append(MAX_ATTEMPTS).append("\n");
        sb.append(won ? "结果：🎉 破解成功！\n" : "结果：💀 破解失败\n");

        sb.append("\n📜 全部记录：\n");
        for (GuessRecord r : history) {
            sb.append("  #").append(r.attempt).append(": ")
              .append(r.guess).append(" → ")
              .append(r.a).append("A").append(r.b).append("B\n");
        }

        // 评分
        if (won) {
            int score = 11 - attempts; // 10次=1分, 1次=10分
            String rating = switch (score) {
                case 10 -> "🏆 神乎其技";
                case 9 -> "🌟 天才推理";
                case 8 -> "✨ 逻辑大师";
                case 7 -> "😊 表现出色";
                case 6 -> "👍 不错哦";
                default -> "💪 继续努力";
            };
            sb.append("\n评级：").append(rating).append("（").append(score).append("分）");
        }

        return sb.toString();
    }

    /** 根据猜测结果给出智能引导 */
    private String smartHint(int a, int b, String guess) {
        StringBuilder sb = new StringBuilder();
        int total = a + b;
        if (total >= 3) {
            sb.append("🔥 很好！有 ").append(total).append(" 个数字是正确的。");
            if (a == CODE_LENGTH - 1) {
                sb.append(" 只差最后一个位置了！\n");
            } else {
                sb.append(" 试试调整这些数字的位置。\n");
            }
        } else if (total >= 2) {
            sb.append("👍 有 ").append(total).append(" 个数字是密码中的。");
            sb.append(" 试着找到更多正确的数字。\n");
        } else if (total == 1) {
            sb.append("🤔 只有 1 个数字是密码中的。\n");
        } else {
            sb.append("❌ 一个数字都没对！换一批数字试试。\n");
        }
        return sb.toString();
    }

    @Override
    public boolean isOver() { return over; }

    @Override
    public String stateContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("【密码破译状态】");
        sb.append("已尝试 ").append(attempts).append("/").append(MAX_ATTEMPTS).append(" 次");
        sb.append("，剩余 ").append(MAX_ATTEMPTS - attempts).append(" 次");
        return sb.toString();
    }
}
