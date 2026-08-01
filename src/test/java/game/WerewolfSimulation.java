package game;

import game.impl.WerewolfEngine;
import org.example.bot.service.AiService;

import java.util.*;
import java.util.stream.*;

/**
 * 狼人杀 6人完整模拟 — 打印完整游戏记录。
 */
public class WerewolfSimulation {

    static final String[] PLAYERS = {"周瑞", "周瑞2", "张子旭", "王恒", "张子旭2", "邢智翔"};
    static WerewolfEngine engine;
    static GameSession gs;
    static Map<String, String> role = new LinkedHashMap<>();
    static Set<String> dead = new LinkedHashSet<>();
    static Random rng = new Random();
    static boolean witchAntidoteUsed;
    static String wolfKill;

    public static void main(String[] args) {
        System.out.println("═".repeat(60));
        System.out.println("  狼人杀 6人局 完整模拟");
        System.out.println("═".repeat(60));

        engine = new WerewolfEngine();
        GameRegistry.register(engine);
        GameRegistry.start(engine, new MockAI(), PLAYERS);
        gs = GameRegistry.session();
        for (int i = 0; i < PLAYERS.length; i++) gs.bindUser("uid_" + PLAYERS[i], PLAYERS[i]);

        // 角色分配
        engine.start(gs);
        for (String p : PLAYERS) role.put(p, gs.playerRole(p));

        phase("玩家加入");
        for (String p : PLAYERS) sayBot(p, "✅ 已加入");
        var wolves = getRole("狼人");
        String witch = getOne("女巫"), seer = getOne("预言家");

        phase("游戏开始 — 角色分配");
        log("DeepSeek 私信角色:");
        for (String p : PLAYERS) {
            String r = role.get(p);
            String msg = "你的身份是" + r;
            if ("狼人".equals(r)) {
                var o = new ArrayList<>(wolves); o.remove(p);
                msg += "，同伴是" + String.join("、", o);
            }
            if ("女巫".equals(r)) msg += "。解药○ 毒药○";
            if ("预言家".equals(r)) msg += "。每晚可查验一人";
            whisper(p, msg);
        }

        phase("全员通知");
        all("🎮 「狼人杀」开始！6位玩家");
        all("🌙 天黑请闭眼...");
        engine.handle(gs, null, "天黑了");

        // === 第1夜 ===
        phase("第1夜 — 狼人行动");
        log("狼人（" + String.join("、", wolves) + "）讨论击杀目标...");
        String killTarget = pick(alive(), wolves.toArray(new String[0]));
        msg(wolves.get(0), "我觉得杀" + killTarget);
        msg(wolves.get(1), "同意，选择杀" + killTarget);
        log("→ 狼人击杀：" + killTarget);
        wolfKill = killTarget;
        engine.handle(gs, null, "【死者:" + killTarget + "】");

        phase("第1夜 — 女巫行动");
        log("女巫（" + witch + "）得知死者：" + killTarget);
        msg(witch, "使用解药救" + killTarget);
        witchAntidoteUsed = true;
        engine.handle(gs, null, "【解药已用】");

        phase("第1夜 — 预言家行动");
        String checkTarget = pick(alive(), witch, seer);
        log("预言家（" + seer + "）查验 " + checkTarget + " → " + role.get(checkTarget));

        engine.handle(gs, null, "天亮了");
        log("解药救活 " + killTarget + "，平安夜！");
        all("☀️ 天亮了！昨晚是【平安夜】，无人死亡。");

        // === 第1天 ===
        phase("第1天 — 警长竞选 + 讨论 + 投票");
        String sheriff = pick(alive());
        all("👑 " + sheriff + " 当选警长！（1.5票）");
        engine.handle(gs, null, "【警长:" + sheriff + "】");
        for (String p : alive()) msg(p, "我是" + role.get(p) + "，凭感觉发言");
        String voteOut = pick(alive());
        kill(voteOut);
        all("🗳 投票结果：" + voteOut + " 被放逐出局");
        log(voteOut + " 遗言：我是" + role.get(voteOut) + "，好人走好！");

        // === 后续轮 ===
        for (int r = 2; !gameOver() && r <= 10; r++) {
            engine.handle(gs, null, "天黑了");
            List<String> al = alive();
            List<String> wl = new ArrayList<>(wolves); wl.retainAll(al);
            String wt = al.contains(witch) ? witch : null;
            String st = al.contains(seer) ? seer : null;

            all("🌙 天黑请闭眼...");
            killTarget = pick(al, wl.toArray(new String[0]));
            if (!wl.isEmpty()) {
                msg(wl.get(0), "杀" + killTarget);
                if (wl.size() > 1) msg(wl.get(1), "同意，选择杀" + killTarget);
                else msg(wl.get(0), "那就选择杀" + killTarget);
            }
            engine.handle(gs, null, "【死者:" + killTarget + "】");
            wolfKill = killTarget;
            boolean saved = false;
            if (wt != null && !witchAntidoteUsed) { witchAntidoteUsed = true; saved = true; engine.handle(gs, null, "【解药已用】"); }
            if (st != null) pick(al, wt, st); // seer checks
            engine.handle(gs, null, "天亮了");
            if (saved) { log("解药救活 " + killTarget); all("☀️ 天亮了！【平安夜】"); }
            else { kill(killTarget); all("☀️ 天亮了！【死者:" + killTarget + "】"); }

            if (gameOver()) break;
            al = alive();
            for (String p : al) msg(p, "我是" + role.get(p) + "，觉得" + pick(al, p) + "可疑");
            voteOut = pick(al);
            kill(voteOut);
            all("🗳 投票结果：" + voteOut + " 被放逐出局（身份:" + role.get(voteOut) + "）");
        }

        phase("游戏结束");
        int wc = 0, gc = 0;
        for (String p : PLAYERS) if (!dead.contains(p)) { if ("狼人".equals(role.get(p))) wc++; else gc++; }
        System.out.println("  🎯 " + (wc >= gc ? "狼人阵营胜利！" : "好人阵营胜利！"));
        System.out.println("  存活狼人:" + wc + " 存活好人:" + gc);
        System.out.println("═".repeat(60));
    }

    static List<String> alive() {
        return Arrays.stream(PLAYERS).filter(p -> !dead.contains(p)).collect(Collectors.toList());
    }
    static List<String> getRole(String r) {
        return Arrays.stream(PLAYERS).filter(p -> r.equals(role.get(p))).collect(Collectors.toList());
    }
    static String getOne(String r) {
        for (String p : PLAYERS) if (r.equals(role.get(p))) return p; return null;
    }
    static boolean gameOver() {
        var al = alive();
        long w = al.stream().filter(p -> "狼人".equals(role.get(p))).count();
        long g = al.size() - w;
        return w == 0 || w >= g;
    }
    static String pick(List<String> from, String... exclude) {
        var l = new ArrayList<>(from); l.removeAll(Arrays.asList(exclude));
        return l.isEmpty() ? from.get(0) : l.get(rng.nextInt(l.size()));
    }
    static void kill(String p) { dead.add(p); engine.handle(gs, null, "【死者:" + p + "】"); }
    static void all(String m) { System.out.println("  📢 " + m); }
    static void sayBot(String p, String m) { System.out.println("  [" + p + "] " + m); }
    static void msg(String p, String m) { System.out.println("  💬 " + p + "(" + role.get(p) + "): " + m); }
    static void whisper(String p, String m) { System.out.println("  📨 → " + p + ": " + m); }
    static void log(String m) { System.out.println("  ◇ " + m); }
    static void phase(String m) { System.out.println("\n── " + m + " ──"); }

    static class MockAI implements AiService {
        @Override public String chat(String uid, String msg) { return "收到。"; }
        @Override public String chatWithTools(String uid, String msg,
            java.util.List<com.openai.models.FunctionDefinition> t,
            java.util.Map<String, java.util.function.Function<com.google.gson.JsonObject, String>> e) { return null; }
        @Override public void record(String uid, String um, String br) {}
        @Override public void setPersona(String uid, String p) {}
        @Override public String getHelpMessage() { return ""; }
    }
}
