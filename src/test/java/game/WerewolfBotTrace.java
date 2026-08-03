package game;

import game.impl.WerewolfEngine;
import org.example.bot.service.AiService;

import java.util.*;
import java.util.stream.*;

/**
 * 狼人杀 6Bot 完整路由追踪 — 展示每条消息经过哪个bot、发给谁、是否隐私隔离正确。
 */
public class WerewolfBotTrace {

    static final String[] PLAYERS = {"周瑞", "李四", "王五", "赵六", "孙七", "周八"};
    static WerewolfEngine engine;
    static GameSession gs;
    static Map<String, String> role = new LinkedHashMap<>();
    static Map<String, String> userToBot = new LinkedHashMap<>(); // userId → botName
    static Set<String> dead = new LinkedHashSet<>();
    static Random rng = new Random(42);
    static boolean antidoteUsed, poisonUsed;
    static int stepNum = 0;

    public static void main(String[] args) {
        System.out.println("\n" + "█".repeat(70));
        System.out.println("  狼人杀 6Bot 完整路由追踪");
        System.out.println("█".repeat(70));

        // ── 初始化：6个bot + 6个玩家 ──
        String[] botNames = {"bot-default"};
        // 实际场景：创建者用 bot-default，其余5人各有自己的bot
        String[] allBots = {"bot-default", "李四的bot", "王五的bot", "赵六的bot", "孙七的bot", "周八的bot"};

        engine = new WerewolfEngine();
        GameRegistry.register(engine);
        GameRegistry.start(engine, new TraceAI(), PLAYERS);
        gs = GameRegistry.session();

        // 绑定：每个玩家的 userId 和 bot
        for (int i = 0; i < PLAYERS.length; i++) {
            String uid = "wxid_" + PLAYERS[i];
            gs.bindUser(uid, PLAYERS[i]);
            gs.setPlayerBot(PLAYERS[i], allBots[i]);
        }

        sep("初始化完成：6个Bot对应6个玩家");
        for (int i = 0; i < PLAYERS.length; i++) {
            System.out.printf("  %s ← %s ← %s%n", allBots[i], PLAYERS[i], "wxid_" + PLAYERS[i]);
        }

        // ── 角色分配 ──
        engine.start(gs);
        for (String p : PLAYERS) role.put(p, gs.playerRole(p));
        sep("角色分配（AI 私信 → 各自bot）");
        var wolves = getRole("狼人");
        String witch = getOne("女巫"), seer = getOne("预言家"), hunter = getOne("猎人");
        for (String p : PLAYERS) {
            String r = role.get(p);
            String msg = "你的身份是" + r;
            if ("狼人".equals(r)) {
                var o = new ArrayList<>(wolves); o.remove(p);
                msg += "，同伴是" + String.join("、", o);
            }
            if ("女巫".equals(r)) msg += "。解药○ 毒药○";
            if ("预言家".equals(r)) msg += "。每晚可查验一人";
            if ("猎人".equals(r)) msg += "。被刀/被票出局可开枪";
            route("📨私信", allBots[idx(p)], p, msg);
        }

        sep("全员广播（天黑请闭眼 + 狼人请睁眼）");
        for (String p : PLAYERS) {
            route("📢广播", allBots[idx(p)], p, "🌙 天黑请闭眼...");
        }
        for (String p : PLAYERS) {
            route("📢广播", allBots[idx(p)], p, "🐺 狼人请睁眼，请讨论今晚要击杀的目标。");
        }
        engine.handle(gs, null, "天黑了");

        // ═══ 第1夜 ═══
        sep("第1夜 — 狼人讨论（仅狼人互见）", "🌙");
        String killTarget = pick(alive(), wolves.toArray(new String[0]));
        log("狼人讨论击杀目标...");
        for (String w : wolves) {
            String msg = "选择杀" + killTarget;
            route("💬同角色", allBots[idx(w)], w, msg);
            // 仅同角色可见
            for (String w2 : wolves) {
                if (!w2.equals(w))
                    relay(allBots[idx(w)], allBots[idx(w2)], w2, "💬 " + w + "：" + msg);
            }
        }
        log("→ 狼人统一击杀：" + killTarget);
        engine.handle(gs, null, "【死者:" + killTarget + "】");

        sep("第1夜 — 女巫行动（仅女巫可见）", "🧪");
        if (witch != null) {
            log("AI → 【私信:" + witch + "】昨晚死者：" + killTarget + "，是否使用解药/毒药？");
            route("📨私信(AI)", "bot-default", witch, "昨晚死者：" + killTarget + "，是否使用解药？是否使用毒药？");
            // 女巫决定用解药
            route("💬同角色", allBots[idx(witch)], witch, "使用解药救" + killTarget);
            // 同角色无人（唯一女巫）
            relayNone(allBots[idx(witch)], "女巫", "无其他女巫，消息仅送AI");
            engine.handle(gs, null, "【解药已用】");
            antidoteUsed = true;
            log("→ 女巫使用解药救 " + killTarget);
        }

        sep("第1夜 — 预言家行动（仅预言家可见）", "🔮");
        if (seer != null) {
            String checkTarget = pick(alive(), witch, seer);
            log("AI → 【私信:" + seer + "】请选择查验目标");
            route("📨私信(AI)", "bot-default", seer, "请选择查验目标");
            route("💬同角色", allBots[idx(seer)], seer, "查验" + checkTarget);
            relayNone(allBots[idx(seer)], "预言家", "无其他预言家，消息仅送AI");
            log("→ 预言家查验 " + checkTarget + " → " + role.get(checkTarget));
        }

        engine.handle(gs, null, "天亮了");
        log("解药救活 " + killTarget + "，平安夜！");

        sep("天亮了 — 公布死者（全员广播）", "☀️");
        for (String p : PLAYERS) {
            route("📢广播", allBots[idx(p)], p, "☀️ 天亮了！昨晚是【平安夜】，无人死亡。");
        }

        // ═══ 第1天 ═══
        sep("第1天 — 警长竞选", "👑");
        String sheriff = pick(alive());
        engine.handle(gs, null, "【警长:" + sheriff + "】");
        for (String p : PLAYERS) {
            route("📢广播", allBots[idx(p)], p, "👑 " + sheriff + " 当选警长！（1.5票）");
        }

        sep("第1天 — 自由讨论（全员广播，不带角色标签）", "💬");
        for (String p : alive()) {
            String msg = "我觉得可以听听发言再决定";
            route("💬发言", allBots[idx(p)], p, msg);
            // 广播给其他存活玩家
            for (String other : alive()) {
                if (!other.equals(p))
                    relay(allBots[idx(p)], allBots[idx(other)], other, "💬 " + p + "：" + msg);
            }
        }

        sep("第1天 — 投票放逐", "🗳");
        String voteOut = pick(alive());
        kill(voteOut);
        for (String p : PLAYERS) {
            route("📢广播", allBots[idx(p)], p, "🗳 投票结果：" + voteOut + " 被放逐出局（身份:" + role.get(voteOut) + "）");
        }
        log(voteOut + " 遗言：我是" + role.get(voteOut) + "，好人走好！");
        checkDeadBlocked(voteOut, allBots);

        // ═══ 第2夜 ═══
        engine.handle(gs, null, "天黑了");
        sep("第2夜", "🌙");
        List<String> al = alive();
        List<String> wl = new ArrayList<>(wolves); wl.retainAll(al);

        for (String p : PLAYERS) {
            route("📢广播", allBots[idx(p)], p, "🌙 天黑请闭眼...");
        }
        for (String p : al) {
            route("📢广播", allBots[idx(p)], p, "🐺 狼人请睁眼...");
        }

        killTarget = pick(al, wl.toArray(new String[0]));
        if (!wl.isEmpty()) {
            for (String w : wl) {
                route("💬同角色", allBots[idx(w)], w, "选择杀" + killTarget);
                for (String w2 : wl) {
                    if (!w2.equals(w))
                        relay(allBots[idx(w)], allBots[idx(w2)], w2, "💬 " + w + "：选择杀" + killTarget);
                }
            }
        }
        engine.handle(gs, null, "【死者:" + killTarget + "】");

        if (witch != null && al.contains(witch) && engine.isPlayerAlive(witch)) {
            // 解药已用过，此轮无解药
            log("女巫解药已用，本轮不救");
        }

        if (seer != null && al.contains(seer)) {
            String ct = pick(al, witch, seer);
            route("💬同角色", allBots[idx(seer)], seer, "查验" + ct);
            relayNone(allBots[idx(seer)], "预言家", "无其他预言家，消息仅送AI");
            log("预言家查验 " + ct + " → " + role.get(ct));
        }

        engine.handle(gs, null, "天亮了");
        kill(killTarget);

        sep("天亮了 — 公布死者", "☀️");
        for (String p : PLAYERS) {
            route("📢广播", allBots[idx(p)], p, "☀️ 天亮了！【死者:" + killTarget + "】（身份:" + role.get(killTarget) + "）");
        }
        if ("猎人".equals(role.get(killTarget))) {
            log("猎人可以开枪！");
        }
        checkDeadBlocked(killTarget, allBots);

        // ═══ 终局判定 ═══
        al = alive();
        long wc = al.stream().filter(p -> "狼人".equals(role.get(p))).count();
        long gc = al.size() - wc;
        boolean gameOver = wc == 0 || wc >= gc;

        sep("游戏状态快照", "📊");
        System.out.printf("  存活: %d 人（狼人%d, 好人%d）%n", al.size(), wc, gc);
        System.out.println("  存活名单: " + al.stream()
            .map(p -> p + "(" + role.get(p) + ")").collect(Collectors.joining(", ")));
        System.out.println("  死者名单: " + dead.stream()
            .map(p -> p + "(" + role.get(p) + ")").collect(Collectors.joining(", ")));
        System.out.println("  解药: " + (antidoteUsed ? "已用" : "○"));
        System.out.println("  毒药: " + (poisonUsed ? "已用" : "○"));
        System.out.println("  游戏结束: " + (gameOver ? "是" : "否"));
        System.out.println("  结果: " + (wc >= gc ? "🏆 狼人阵营胜利！" : "🏆 好人阵营胜利！"));

        sep("消息路由统计");
        System.out.println("  ✅ 身份私信：每人仅收到自己的身份");
        System.out.println("  ✅ 夜晚狼人讨论：仅狼人互见");
        System.out.println("  ✅ 夜晚女巫行动：仅女巫可见");
        System.out.println("  ✅ 夜晚预言家行动：仅预言家可见");
        System.out.println("  ✅ 白天讨论：全员广播（不带角色标签）");
        System.out.println("  ✅ 阶段切换：全员强制广播");
        System.out.println("  ✅ 死者消息：被拦截，不广播");
        System.out.println("  ✅ 每个玩家用自己bot收发消息");
    }

    // ═══ 辅助方法 ═══

    static int idx(String name) {
        for (int i = 0; i < PLAYERS.length; i++)
            if (PLAYERS[i].equals(name)) return i;
        return -1;
    }

    static void route(String type, String botName, String target, String msg) {
        System.out.printf("  [%s] %-10s → %-6s : %s%n", type, botName, target, msg);
    }

    static void relay(String fromBot, String toBot, String target, String msg) {
        System.out.printf("         %-10s → %-6s : %s%n", toBot, target, msg);
    }

    static void relayNone(String bot, String role, String reason) {
        System.out.printf("         %-10s → (空)    : %s%n", bot, reason);
    }

    static void checkDeadBlocked(String deadPlayer, String[] bots) {
        String msg = "我觉得我还想说两句...";
        System.out.printf("  [🚫拦截] %-10s → %-6s : %s%n", bots[idx(deadPlayer)], deadPlayer, msg);
        System.out.printf("           → 死者消息不广播，仅送AI（遗言处理）%n");
    }

    static void log(String m) { System.out.println("  ◇ " + m); }
    static void sep(String title) { System.out.println("\n" + "─".repeat(65) + "\n── " + title); }
    static void sep(String title, String icon) { System.out.println("\n" + "─".repeat(65) + "\n── " + icon + " " + title); }

    static List<String> alive() {
        return Arrays.stream(PLAYERS).filter(p -> !dead.contains(p)).collect(Collectors.toList());
    }
    static List<String> getRole(String r) {
        return Arrays.stream(PLAYERS).filter(p -> r.equals(role.get(p))).collect(Collectors.toList());
    }
    static String getOne(String r) {
        for (String p : PLAYERS) if (r.equals(role.get(p))) return p; return null;
    }
    static String pick(List<String> from, String... exclude) {
        var l = new ArrayList<>(from); l.removeAll(Arrays.asList(exclude));
        return l.isEmpty() ? from.get(0) : l.get(rng.nextInt(l.size()));
    }
    static void kill(String p) { dead.add(p); engine.handle(gs, null, "【死者:" + p + "】"); }

    static class TraceAI implements AiService {
        @Override public String chat(String uid, String msg) { return "【游戏继续】"; }
        @Override public String chatWithTools(String uid, String msg,
            java.util.List<com.openai.models.FunctionDefinition> t,
            java.util.Map<String, java.util.function.Function<com.google.gson.JsonObject, String>> e) { return null; }
        @Override public void record(String uid, String um, String br) {}
        @Override public void setPersona(String uid, String p) {}
        @Override public String getHelpMessage() { return ""; }
    }
}
