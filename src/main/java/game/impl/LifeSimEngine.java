package game.impl;

import game.GameSession;
import game.GameEngine;
import game.GameRegistry;
import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * 模拟人生 — 数据驱动重构版。
 * 天赋系统 + JSON事件库 + 社交关系 + 职业体系 + AI叙事。
 */
public class LifeSimEngine implements GameEngine {

    // === 属性 ===
    private String name, gender;
    private int age;
    private int intel, physique, charm, wealth, mood, health; // 0-100
    private int luck, morality; // 隐藏属性 0-100
    private int lowMoodTurns; // 连续低心情回合数
    private Stage stage = Stage.BABY;
    private int maxAge = 75; // 自然寿命
    private boolean over, named;
    private GameEvent currentEvent; // 当前展示的事件，applyChoice用
    private final Random rng = new Random();

    // === 天赋 ===
    private final List<Talent> allTalents = new ArrayList<>();
    private final List<Talent> drawnTalents = new ArrayList<>();
    private final List<Talent> activeTalents = new ArrayList<>();

    // === 社交 ===
    private int parentsRel = 50, partnerRel = 0, childrenRel = 0, bestFriendRel = 0;
    private boolean hasPartner, hasChildren, hasBestFriend;

    // === 职业 ===
    private String job = "无", education = "无";
    private int careerLvl;

    // === 事件 ===
    private final List<GameEvent> allEvents = new ArrayList<>();
    private final Map<Stage,List<GameEvent>> eventMap = new LinkedHashMap<>();
    private final Set<String> triggeredEvents = new HashSet<>();

    enum Stage { BABY, CHILD, TEEN, YOUNG, MIDDLE, ELDER }
    enum Phase { TALENT_SELECT, NAMING, PLAYING, END }

    private Phase phase = Phase.TALENT_SELECT;

    @Override public String name() { return "模拟人生"; }
    @Override public int minPlayers() { return 1; }
    @Override public int maxPlayers() { return 1; }

    @Override public String systemPrompt() {
        return "你是一个安静的观察者，透过窗户看着" + name + "的人生。\n用具体的画面和细节叙述——光线、声音、表情。120-160字。";
    }

    // ==================== 初始化 ====================

    @Override public String start(GameSession session) {
        age = 0; intel = 20+rng.nextInt(10); physique = 20+rng.nextInt(10);
        charm = 20+rng.nextInt(10); wealth = 20+rng.nextInt(10);
        mood = 60+rng.nextInt(20); health = 70+rng.nextInt(15);
        luck = 30+rng.nextInt(30); morality = 40+rng.nextInt(20);
        lowMoodTurns = 0; maxAge = 75+rng.nextInt(20);
        stage = Stage.BABY; over = false; named = false;
        parentsRel = 60; partnerRel = 0; childrenRel = 0; bestFriendRel = 0;
        hasPartner = hasChildren = hasBestFriend = false;
        job = "无"; education = "无"; careerLvl = 0;
        activeTalents.clear(); triggeredEvents.clear();
        phase = Phase.TALENT_SELECT;

        gender = rng.nextBoolean() ? "男" : "女";
        loadTalents(); loadEvents();
        drawTalents();
        return null;
    }

    // ==================== 数据加载 ====================

    private void loadTalents() {
        if (!allTalents.isEmpty()) return;
        try {
            String json = Files.readString(Path.of("src/main/java/game/impl/talents.json"));
            Talent[] arr = new Gson().fromJson(json, Talent[].class);
            allTalents.addAll(Arrays.asList(arr));
        } catch (IOException e) { System.err.println("[人生] 天赋加载失败: " + e.getMessage()); }
    }

    private void loadEvents() {
        if (!allEvents.isEmpty()) return;
        try {
            String json = Files.readString(Path.of("src/main/java/game/impl/events.json"));
            EventWrapper w = new Gson().fromJson(json, EventWrapper.class);
            allEvents.addAll(w.events);
            for (Stage s : Stage.values()) eventMap.put(s, new ArrayList<>());
            for (GameEvent e : allEvents) {
                Stage s = Stage.valueOf(e.stage);
                eventMap.get(s).add(e);
            }
        } catch (IOException e) { System.err.println("[人生] 事件加载失败: " + e.getMessage()); }
    }

    private void drawTalents() {
        drawnTalents.clear();
        List<Talent> pool = new ArrayList<>(allTalents);
        Collections.shuffle(pool, rng);
        // 10抽：至少1金2紫4蓝
        List<Talent> golds = pool.stream().filter(t->t.rarity.equals("gold")).limit(1).toList();
        List<Talent> purps = pool.stream().filter(t->t.rarity.equals("purple")).limit(2).toList();
        List<Talent> blues = pool.stream().filter(t->t.rarity.equals("blue")).limit(4).toList();
        List<Talent> rest = pool.stream().filter(t->!golds.contains(t)&&!purps.contains(t)&&!blues.contains(t)).limit(3).toList();
        drawnTalents.addAll(golds); drawnTalents.addAll(purps);
        drawnTalents.addAll(blues); drawnTalents.addAll(rest);
        while (drawnTalents.size() < 10) drawnTalents.add(pool.get(rng.nextInt(pool.size())));
        Collections.shuffle(drawnTalents, rng);
    }

    // ==================== 欢迎消息 ====================

    public String welcomeMessage() {
        StringBuilder sb = new StringBuilder("🎮 模拟人生\n\n🌟 抽到10个天赋，回复数字选3个：\n\n");
        for (int i = 0; i < drawnTalents.size(); i++) {
            Talent t = drawnTalents.get(i);
            sb.append(rarityIcon(t.rarity)).append(i+1).append(".").append(t.name);
            if (i % 2 == 0) sb.append("  "); else sb.append("\n");
        }
        sb.append("\n💡 输入3个数字（如「1 3 7」）");
        return sb.toString();
    }

    private String rarityIcon(String r) {
        return switch (r) { case "gold"->"🟡"; case "purple"->"🟣"; case "blue"->"🔵"; case "red"->"🔴"; default->"⚪"; };
    }

    // ==================== 主循环 ====================

    @Override public String handle(GameSession session, String userId, String text) {
        if (over) return "游戏已结束。输入 /restart 重开新人生。";
        if (text.equals("/state")) return stateView();
        if (text.equals("/help")) return helpText();
        if (text.equals("/restart")) { start(session); return "🔄 已重置。\n\n" + welcomeMessage(); }

        text = text.strip();

        // 天赋选择
        if (phase == Phase.TALENT_SELECT) {
            int[] picks = parseNumbers(text);
            if (picks.length < 3) return "请选择 **3个** 天赋，用空格或逗号分隔，如「1 3 7」";
            activeTalents.clear();
            for (int p : picks) {
                if (p >= 1 && p <= drawnTalents.size()) activeTalents.add(drawnTalents.get(p-1));
            }
            if (activeTalents.size() < 3) return "请选满3个天赋。";
            applyTalents();
            phase = Phase.NAMING;
            return "✅ 天赋已生效！\n\n请给" + (gender.equals("男")?"他":"她") + "取个名字吧～";
        }

        // 取名
        if (phase == Phase.NAMING) {
            name = text.replaceAll("[，。！？\\s]", "");
            if (name.length() > 8) name = name.substring(0, 8);
            named = true; phase = Phase.PLAYING;
            return "✅ **" + name + "**\n\n" + nextEvent();
        }

        // 游戏进行中
        if (phase == Phase.PLAYING) {
            int[] picks = parseNumbers(text);
            if (picks.length == 0) return "请回复数字选择选项。";
            return applyChoice(picks[0]);
        }

        return "";
    }

    // ==================== 事件与选项 ====================

    private String nextEvent() {
        if (health <= 0) { over = true; return deathEnding(); }
        if (age >= maxAge) { over = true; return naturalEnding(); }

        // 阶段切换
        Stage newStage = getStage();
        if (newStage != stage) {
            stage = newStage;
            GameEvent m = findMilestone();
            if (m != null) { currentEvent = m; return formatEvent(m); }
        }

        // 随机事件
        List<GameEvent> pool = eventMap.getOrDefault(stage, List.of());
        final int curAge = age;
        List<GameEvent> available = pool.stream()
            .filter(e -> "random".equals(e.type))
            .filter(e -> e.ageMatch(curAge))
            .filter(e -> e.talentMatch(activeTalents))
            .filter(e -> !triggeredEvents.contains(e.title))
            .collect(Collectors.toList());
        if (available.isEmpty()) {
            triggeredEvents.clear();
            available = pool.stream().filter(e -> "random".equals(e.type)).collect(Collectors.toList());
        }

        currentEvent = available.get(rng.nextInt(available.size()));
        triggeredEvents.add(currentEvent.title);
        age += 1 + rng.nextInt(2);
        return formatEvent(currentEvent);
    }

    private String applyChoice(int choiceIdx) {
        if (currentEvent == null || choiceIdx < 1 || choiceIdx > currentEvent.opts.size())
            return "请回复数字选择对应选项。";

        EventOpt opt = currentEvent.opts.get(choiceIdx - 1);
        applyEffects(opt.eff);

        // 低心情检测
        if (mood < 20) lowMoodTurns++; else lowMoodTurns = 0;
        if (lowMoodTurns >= 3) {
            mood -= 10; health -= 10;
            lowMoodTurns = 0;
        }
        if (health <= 0) { over = true; return deathEnding(); }
        if (age >= maxAge) { over = true; return naturalEnding(); }

        // AI 叙事
        String aiPrompt = name + "，" + age + "岁，" + getStageLabel()
            + "。事件：" + currentEvent.desc + "，" + name + "选择了：" + opt.text
            + "。智力" + intel + "体质" + physique + "魅力" + charm
            + "心情" + mood + "健康" + health
            + "。请以观察者视角描述这个场景，有画面感，100-140字。";

        String story = GameRegistry.session() != null ? GameRegistry.session().prompt(aiPrompt) : null;

        StringBuilder result = new StringBuilder();
        result.append(story != null ? story : opt.text).append("\n\n");
        result.append(statCompact()).append("\n");
        result.append(effToEmoji(opt.eff));
        return result + "\n\n" + nextEvent();
    }

    // ==================== 属性影响 ====================

    private void applyEffects(String effStr) {
        if (effStr == null) return;
        for (String part : effStr.split("\\s+")) {
            if (part.isEmpty()) continue;
            // 解析 health-5 或 wealth+3
            boolean neg = false;
            String kvPart = part;
            if (part.contains("-") && !part.startsWith("-")) {
                neg = true;
                kvPart = part.replace("-", "+");
            }
            String[] kv = kvPart.split("[+=]");
            if (kv.length != 2) continue;
            int val = Integer.parseInt(kv[1]);
            if (neg) val = -val;
            double rate = getGrowthRate(kv[0]);
            int actual = (int)(val * rate);
            switch (kv[0]) {
                case "intel" -> intel = c(intel+actual);
                case "physique" -> physique = c(physique+actual);
                case "charm" -> charm = c(charm+actual);
                case "wealth" -> wealth = c(wealth+actual);
                case "mood" -> mood = c(mood+actual);
                case "health" -> health = c(health+actual);
                case "luck" -> luck = c(luck+actual);
                case "morality" -> morality = c(morality+actual);
                case "parentsRelation" -> parentsRel = c(parentsRel+actual);
                case "partnerRelation" -> partnerRel += actual;
                case "childrenRelation" -> childrenRel += actual;
                case "bestFriendRelation" -> bestFriendRel += actual;
                case "stress" -> mood = c(mood - actual); // backward compat
            }
        }
    }

    private double getGrowthRate(String attr) {
        double rate = 1.0;
        for (Talent t : activeTalents) {
            switch (attr) {
                case "intel" -> { if (t.intelRate > 0) rate *= t.intelRate; }
                case "physique" -> { if (t.physiqueRate > 0) rate *= t.physiqueRate; }
                case "charm" -> { if (t.charmRate > 0) rate *= t.charmRate; }
                case "wealth" -> { if (t.wealthRate > 0) rate *= t.wealthRate; }
                case "health" -> { if (t.healthRate > 0) rate *= t.healthRate; }
            }
        }
        return rate;
    }

    private void applyTalents() {
        for (Talent t : activeTalents) {
            if (t.intel != 0) intel = c(intel+t.intel);
            if (t.physique != 0) physique = c(physique+t.physique);
            if (t.charm != 0) charm = c(charm+t.charm);
            if (t.wealth != 0) wealth = c(wealth+t.wealth);
            if (t.mood != 0) mood = c(mood+t.mood);
            if (t.health != 0) health = c(health+t.health);
            if (t.luck != 0) luck = c(luck+t.luck);
            if (t.morality != 0) morality = c(morality+t.morality);
            if (t.parentsRelation != 0) parentsRel = c(parentsRel+t.parentsRelation);
        }
    }

    // ==================== 事件搜索 ====================

    private GameEvent findMilestone() {
        List<GameEvent> pool = eventMap.getOrDefault(stage, List.of());
        List<GameEvent> milestones = pool.stream().filter(e -> e.type.equals("milestone")).toList();
        if (milestones.isEmpty()) return pool.isEmpty() ? null : pool.get(0);
        return milestones.get(rng.nextInt(milestones.size()));
    }

    // ==================== 格式化 ====================

    private String formatEvent(GameEvent evt) {
        StringBuilder sb = new StringBuilder();
        sb.append("━━━ ").append(age).append("岁 · ").append(getStageLabel()).append(" ━━━\n\n");
        sb.append(statCompact());
        if (!activeTalents.isEmpty()) {
            sb.append("\n🌟 ").append(activeTalents.stream().map(t->t.name).collect(Collectors.joining(" ")));
        }
        sb.append("\n\n📌 ").append(evt.desc).append("\n");
        for (int i = 0; i < evt.opts.size(); i++) {
            EventOpt o = evt.opts.get(i);
            sb.append("\n").append(i+1).append(". ").append(o.text);
            if (o.needTalent != null && !o.needTalent.isEmpty())
                sb.append(" 🔒需").append(o.needTalent);
        }
        sb.append("\n\n💡 /state /help /restart");
        return sb.toString();
    }

    private String effToEmoji(String eff) {
        if (eff == null || eff.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : eff.split("\\s+")) {
            if (p.isEmpty()) continue;
            boolean neg = false;
            String kvPart = p;
            if (p.contains("-") && !p.startsWith("-")) { neg = true; kvPart = p.replace("-", "+"); }
            String[] kv = kvPart.split("[+=]");
            if (kv.length != 2) continue;
            int v = Integer.parseInt(kv[1]);
            if (neg) v = -v;
            String icon = switch (kv[0]) {
                case "intel"->"🧠"; case "physique"->"💪"; case "charm"->"💃";
                case "wealth"->"💰"; case "mood"->"😊"; case "health"->"❤️";
                case "luck"->"🍀"; case "morality"->"⚖️";
                case "parentsRelation"->"👨‍👩‍👧";
                default -> kv[0];
            };
            sb.append(icon).append(v >= 0 ? "+" : "").append(v).append(" ");
        }
        return sb.toString().strip();
    }

    private String statCompact() {
        return String.format("🧠智力%d  💪体质%d  💃魅力%d\n💰财富%d  😊心情%d  ❤️健康%d",
            intel, physique, charm, wealth, mood, health);
    }

    private String stateView() {
        StringBuilder sb = new StringBuilder("📊 **" + name + "** " + age + "岁 " + getStageLabel() + "\n\n");
        sb.append("🧠智力").append(intel).append(" 💪体质").append(physique).append(" 💃魅力").append(charm).append("\n");
        sb.append("💰财富").append(wealth).append(" 😊心情").append(mood).append(" ❤️健康").append(health).append("\n");
        sb.append("🍀幸运").append(luck).append(" ⚖️道德").append(morality).append("\n\n");
        sb.append("👨‍👩‍👧父母").append(parentsRel);
        if (hasPartner) sb.append(" 💕伴侣").append(partnerRel);
        if (hasBestFriend) sb.append(" 👫挚友").append(bestFriendRel);
        sb.append("\n💼").append(job).append(" 🎓").append(education).append("\n\n");
        sb.append("🌟天赋：");
        for (Talent t : activeTalents) sb.append(t.name).append(" ");
        return sb.toString();
    }

    private String helpText() {
        return "🎮 **模拟人生**\n\n回复数字选择选项推进游戏。\n/state 查看完整属性\n/restart 重新开始\n/help 查看此帮助";
    }

    // ==================== 结局 ====================

    private String deathEnding() {
        String rating = calcRating();
        return "💀 " + name + "在" + age + "岁因病离世。\n\n" + endingReport(rating);
    }

    private String naturalEnding() {
        String rating = calcRating();
        return "🕊 " + name + "在" + age + "岁安详离世。\n\n" + endingReport(rating);
    }

    private String calcRating() {
        int score = intel+physique+charm+wealth+mood+health+morality;
        if (score >= 500) return "🏆 传奇人生";
        if (score >= 400) return "🌟 卓越人生";
        if (score >= 300) return "😊 圆满人生";
        if (score >= 200) return "📋 平凡人生";
        return "💧 潦倒人生";
    }

    private String endingReport(String rating) {
        return "══ 📊 人生报告 ══\n"
            + name + " " + age + "岁 " + gender + " " + rating + "\n\n"
            + "🧠智力" + intel + " 💪体质" + physique + " 💃魅力" + charm + "\n"
            + "💰财富" + wealth + " 😊心情" + mood + " ❤️健康" + health + "\n"
            + "🍀幸运" + luck + " ⚖️道德" + morality + "\n\n"
            + "👨‍👩‍👧父母" + parentsRel + (hasPartner?" 💕伴侣"+partnerRel:"")
            + "\n💼" + job + " 🎓" + education + "\n\n"
            + "🌟天赋：" + activeTalents.stream().map(t->t.name).collect(Collectors.joining(" "))
            + "\n\n══ ══ ══ ══\n输入 /restart 开启新人生。";
    }

    // ==================== 工具方法 ====================

    private Stage getStage() {
        if (age <= 3) return Stage.BABY;
        if (age <= 12) return Stage.CHILD;
        if (age <= 18) return Stage.TEEN;
        if (age <= 35) return Stage.YOUNG;
        if (age <= 55) return Stage.MIDDLE;
        return Stage.ELDER;
    }

    private String getStageLabel() {
        Stage s = getStage();
        return switch (s) { case BABY->"婴儿"; case CHILD->"童年"; case TEEN->"青少年"; case YOUNG->"青年"; case MIDDLE->"中年"; case ELDER->"老年"; };
    }

    private int[] parseNumbers(String text) {
        return Arrays.stream(text.replaceAll("[^0-9]+", " ").trim().split("\\s+"))
            .filter(s -> !s.isEmpty()).mapToInt(Integer::parseInt).distinct().toArray();
    }

    private int c(int v) { return Math.max(0, Math.min(100, v)); }

    @Override public boolean isOver() { return over; }
    @Override public String stateContext() { return name + " " + age + "岁 " + getStageLabel(); }

    // ==================== 数据类 ====================

    static class EventWrapper { List<GameEvent> events; }
    static class GameEvent {
        String id, stage, type, title, desc, reqTalent;
        int minAge = -1, maxAge = 999;
        List<EventOpt> opts;
        boolean ageMatch(int a) { return a >= minAge && a <= maxAge; }
        boolean talentMatch(List<Talent> active) {
            if (reqTalent == null || reqTalent.isEmpty()) return true;
            return active.stream().anyMatch(t -> t.name.equals(reqTalent));
        }
    }
    static class EventOpt { String text, eff, needTalent; }
    static class Talent {
        String id, name, rarity, desc;
        double intelRate=1.0, physiqueRate=1.0, charmRate=1.0, wealthRate=1.0, moodRate=1.0, healthRate=1.0;
        double relationRate=1.0, spendRate=1.0;
        int intel, physique, charm, wealth, mood, health, luck, morality;
        int parentsRelation, partnerRelation;
        double sportBonus, investBonus, artBonus, craftBonus, travelBonus, eventBonus, badEventRate;
        int moodMin;
        int healthMax;
    }
}
