package game.impl;

import game.GameSession;
import game.GameEngine;
import game.GameRegistry;
import com.google.gson.Gson;
import java.io.*;
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
        String n = name != null ? name : "这个孩子";
        return "你是一个安静的观察者，透过窗户看着" + n + "的人生。\n用具体的画面和细节叙述——光线、声音、表情。120-160字。";
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
        try (var in = getClass().getResourceAsStream("talents.json")) {
            if (in == null) { System.err.println("[人生] ⚠ talents.json 未找到"); return; }
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Talent[] arr = new Gson().fromJson(json, Talent[].class);
            allTalents.addAll(Arrays.asList(arr));
        } catch (IOException e) { System.err.println("[人生] 天赋加载失败: " + e.getMessage()); }
    }

    private void loadEvents() {
        if (!allEvents.isEmpty()) return;
        try (var in = getClass().getResourceAsStream("events.json")) {
            if (in == null) { System.err.println("[人生] ⚠ events.json 未找到"); return; }
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
        if (allTalents.isEmpty()) {
            // JSON 加载失败时的硬兜底——避免死循环
            System.err.println("[人生] ⚠ 天赋池为空，使用内置兜底天赋");
            allTalents.add(new Talent() {{ id="fallback1"; name="普通人生"; rarity="white"; desc="平凡起点"; }});
            allTalents.add(new Talent() {{ id="fallback2"; name="身体健康"; rarity="blue"; desc="体质+10"; physique=10; }});
            allTalents.add(new Talent() {{ id="fallback3"; name="头脑灵活"; rarity="blue"; desc="智力+10"; intel=10; }});
            allTalents.add(new Talent() {{ id="fallback4"; name="天生乐观"; rarity="white"; desc="心情+10"; mood=10; }});
        }
        List<Talent> pool = new ArrayList<>(allTalents);
        Collections.shuffle(pool, rng);
        // 10抽：至少1金2紫4蓝
        List<Talent> golds = pool.stream().filter(t->t.rarity.equals("gold")).limit(1).toList();
        List<Talent> purps = pool.stream().filter(t->t.rarity.equals("purple")).limit(2).toList();
        List<Talent> blues = pool.stream().filter(t->t.rarity.equals("blue")).limit(4).toList();
        List<Talent> rest = pool.stream().filter(t->!golds.contains(t)&&!purps.contains(t)&&!blues.contains(t)).limit(3).toList();
        drawnTalents.addAll(golds); drawnTalents.addAll(purps);
        drawnTalents.addAll(blues); drawnTalents.addAll(rest);
        // 补足到 10 个：从未入选的天赋里抽，避免重复天赋
        List<Talent> remaining = pool.stream().filter(t -> !drawnTalents.contains(t)).toList();
        while (drawnTalents.size() < 10 && !remaining.isEmpty()) {
            drawnTalents.add(remaining.get(rng.nextInt(remaining.size())));
            remaining = pool.stream().filter(t -> !drawnTalents.contains(t)).toList();
        }
        // 极端兜底：若天赋池本身不足 10 个，允许重复（避免死循环）
        while (drawnTalents.size() < 10 && !pool.isEmpty()) {
            drawnTalents.add(pool.get(rng.nextInt(pool.size())));
        }
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
            // 清洗：去换行、控制字符、中英文标点、方括号，只保留中英文数字
            name = text.replaceAll("[\\p{Cntrl}\\[\\]【】{}()（）\"'`，。！？：；、\\s]", "");
            if (name.isEmpty()) name = "无名";
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
        // 先推进年龄，再按新年龄选择事件 —— 保证展示年龄与事件一致（修复「3岁做一岁抓周」）
        age += 1 + rng.nextInt(2);
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
            // 兜底重筛：仍保留 ageMatch/talentMatch，避免出现「无天赋却弹出需天赋事件」的违和
            available = pool.stream()
                .filter(e -> "random".equals(e.type))
                .filter(e -> e.ageMatch(curAge))
                .filter(e -> e.talentMatch(activeTalents))
                .collect(Collectors.toList());
        }
        // 硬兜底：如果该阶段实在没有事件，直接平静度过
        if (available.isEmpty()) {
            currentEvent = null; // 无事件可选项，防止玩家对上一次事件重复结算
            return "⏳ " + name + " " + age + "岁，" + getStageLabel() + "。日子平静地流淌…\n\n"
                + statCompactRich() + "\n\n💡 /state /help /restart";
        }

        // 加权随机：坏事件被「倒霉体质」加重，好事件被「天选之子」加重
        currentEvent = pickRandomEvent(available);
        triggeredEvents.add(currentEvent.title);
        return formatEvent(currentEvent);
    }

    private String applyChoice(int choiceIdx) {
        if (currentEvent == null || currentEvent.opts == null
            || choiceIdx < 1 || choiceIdx > currentEvent.opts.size())
            return "请回复数字选择对应选项。";

        EventOpt opt = currentEvent.opts.get(choiceIdx - 1);

        // 天赋解锁选项拦截
        if (!opt.availableTo(activeTalents)) {
            String need = opt.needDesc();
            return "🔒 这个选项需要天赋「" + (need != null ? need : "未知") + "」，你还没有。\n"
                + "换个选项试试，或回顾一下已选天赋。";
        }

        Map<String,Integer> delta = applyEffectsWithDelta(opt.eff, currentEvent.category);

        // 低心情检测（天赋 moodMin 防止心情过低，如「乐观性格」心情下限+15）
        int minMood = getMoodMin();
        int depressThreshold = Math.max(20, minMood);
        if (mood < depressThreshold) lowMoodTurns++; else lowMoodTurns = 0;
        if (lowMoodTurns >= 3 && mood < depressThreshold) {
            mood -= 10; health -= 10;
            lowMoodTurns = 0;
        }
        if (health <= 0) { over = true; return deathEnding(); }
        if (age >= maxAge) { over = true; return naturalEnding(); }

        // AI 叙事
        String aiPrompt = buildAiPrompt(currentEvent, opt, delta);
        String story = GameRegistry.session() != null ? GameRegistry.session().prompt(aiPrompt) : null;

        StringBuilder result = new StringBuilder();
        result.append(story != null ? story : opt.text).append("\n\n");
        result.append(statCompactRich()).append("\n");
        result.append(effToEmoji(delta));
        // 叙事 + 下一事件选项一起返回（年龄已先推进，事件与年龄一致不会错位）
        result.append("\n\n──────────────\n");
        return result + nextEvent();
    }

    /** 构造 AI 叙事 prompt：喂全量属性 + 天赋 + 关系 + 职业，并要求叙述与数值一致 */
    private String buildAiPrompt(GameEvent evt, EventOpt opt, Map<String,Integer> delta) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前人生】").append(name).append("，").append(age).append("岁，").append(getStageLabel());
        if (job != null && !"无".equals(job)) sb.append("，职业：").append(job);
        sb.append("\n【属性】智力").append(intel).append("/100 体质").append(physique).append("/100 魅力").append(charm).append("/100")
          .append("\n财富").append(wealth).append("/100 心情").append(mood).append("/100 健康").append(health).append("/100")
          .append(" 幸运").append(luck).append("/100 道德").append(morality).append("/100");
        // 本次选择带来的属性变化 —— 让 AI 感知「发烧后心情下降」这类因果，叙事贴合新状态
        if (delta != null && !delta.isEmpty()) {
            sb.append("\n【本次变化】");
            List<String> parts = new ArrayList<>();
            for (var e : delta.entrySet()) {
                String name = switch (e.getKey()) {
                    case "intel" -> "智力"; case "physique" -> "体质"; case "charm" -> "魅力";
                    case "wealth" -> "财富"; case "mood" -> "心情"; case "health" -> "健康";
                    case "luck" -> "幸运"; case "morality" -> "道德";
                    case "parentsRelation" -> "父母关系"; case "partnerRelation" -> "伴侣关系";
                    default -> e.getKey();
                };
                parts.add(name + (e.getValue() >= 0 ? "+" : "") + e.getValue());
            }
            sb.append(String.join("、", parts));
        }
        if (!activeTalents.isEmpty()) {
            sb.append("\n【天赋】").append(activeTalents.stream()
                .map(t -> t.name + "（" + t.desc + "）").collect(Collectors.joining("、")));
        }
        sb.append("\n【关系】父母").append(parentsRel);
        if (hasPartner) sb.append(" 伴侣").append(partnerRel);
        if (hasBestFriend) sb.append(" 挚友").append(bestFriendRel);
        if (hasChildren) sb.append(" 子女").append(childrenRel);
        sb.append("\n【事件】").append(evt.desc);
        sb.append("\n【选择】").append(name).append("选择了：").append(opt.text);
        String hint = talentHintFor(evt);
        if (!hint.isEmpty()) sb.append(hint);
        sb.append("\n\n请以观察者视角叙述这一幕，有画面感（光线、声音、表情），120-160字。")
          .append("重要：叙述必须与上面数值一致——数值高就写从容/出众/富足，数值低就写吃力/窘迫/拮据，")
          .append("严禁出现与数值明显矛盾的情节（例如财富很低却写「豪掷千金」）。");
        return sb.toString();
    }

    // ==================== 属性影响 ====================

    private void applyEffects(String effStr) {
        applyEffectsWithDelta(effStr, null);
    }

    /**
     * 应用效果并返回每个属性的实际增量（含成长倍率/类别加成/支出打折），
     * 供 effToEmoji 按真实生效值展示。category 为 null 时行为与原 applyEffects 一致。
     */
    private Map<String,Integer> applyEffectsWithDelta(String effStr, String category) {
        Map<String,Integer> delta = new LinkedHashMap<>();
        if (effStr == null) return delta;
        double catBonus = getCategoryBonus(category);
        double spendRate = getSpendRate();
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
            // 类别正向加成：运动/理财/艺术…天赋让正向效果放大
            if (val > 0 && catBonus > 0) rate *= (1.0 + catBonus);
            // 支出打折：勤俭持家等让财富扣减少一些
            if ("wealth".equals(kv[0]) && val < 0 && spendRate < 1.0) rate *= spendRate;
            int actual = (int)(val * rate);
            delta.put(kv[0], actual);
            switch (kv[0]) {
                case "intel" -> intel = cStd(intel+actual);
                case "physique" -> physique = cStd(physique+actual);
                case "charm" -> charm = cStd(charm+actual);
                case "wealth" -> wealth = cStd(wealth+actual);
                case "mood" -> { mood = cStd(mood+actual); clampMood(); } // 乐观性格等天赋保证心情不下限
                case "health" -> health = c(health+actual);
                case "luck" -> luck = cStd(luck+actual);
                case "morality" -> morality = cStd(morality+actual);
                case "parentsRelation" -> { int v = applyRelationBonus(actual); parentsRel = cStd(parentsRel+v); delta.put(kv[0], v); }
                case "partnerRelation" -> { int v = applyRelationBonus(actual); partnerRel = cStd(partnerRel+v); delta.put(kv[0], v); }
                case "childrenRelation" -> { int v = applyRelationBonus(actual); childrenRel = cStd(childrenRel+v); delta.put(kv[0], v); }
                case "bestFriendRelation" -> { int v = applyRelationBonus(actual); bestFriendRel = cStd(bestFriendRel+v); delta.put(kv[0], v); }
                case "stress" -> { mood = cStd(mood - actual); clampMood(); } // backward compat
            }
        }
        return delta;
    }

    private double getGrowthRate(String attr) {
        double rate = 1.0;
        for (Talent t : activeTalents) {
            switch (attr) {
                case "intel" -> { if (t.intelRate > 0) rate *= t.intelRate; }
                case "physique" -> { if (t.physiqueRate > 0) rate *= t.physiqueRate; }
                case "charm" -> { if (t.charmRate > 0) rate *= t.charmRate; }
                case "wealth" -> { if (t.wealthRate > 0) rate *= t.wealthRate; }
                case "mood" -> { if (t.moodRate > 0) rate *= t.moodRate; }
                case "health" -> { if (t.healthRate > 0) rate *= t.healthRate; }
                case "parentsRelation" -> { if (t.relationRate > 0) rate *= t.relationRate; }
                case "partnerRelation" -> { if (t.relationRate > 0) rate *= t.relationRate; }
                case "childrenRelation" -> { if (t.relationRate > 0) rate *= t.relationRate; }
                case "bestFriendRelation" -> { if (t.relationRate > 0) rate *= t.relationRate; }
            }
        }
        return rate;
    }

    /** 关系加成：乘算倍率之后，再加 relationBonus（如「社交达人」+30%）。 */
    private int applyRelationBonus(int actual) {
        double bonus = 0.0;
        for (Talent t : activeTalents) { bonus += t.relationBonus; }
        return bonus != 0.0 ? (int)(actual * (1.0 + bonus)) : actual;
    }

    // ==================== 天赋类别加成（激活死字段） ====================

    /** 事件类别对应天赋的加成总和（sportBonus→sport、investBonus→invest…），无匹配返回 0 */
    private double getCategoryBonus(String category) {
        if (category == null || category.isBlank()) return 0.0;
        double bonus = 0.0;
        for (Talent t : activeTalents) {
            switch (category) {
                case "sport" -> bonus += t.sportBonus;
                case "invest" -> bonus += t.investBonus;
                case "art" -> bonus += t.artBonus;
                case "craft" -> bonus += t.craftBonus;
                case "travel" -> bonus += t.travelBonus;
                default -> {}
            }
        }
        return bonus;
    }

    /** 拥有某事件类别加成的天赋列表（用于 AI 提示行） */
    private List<Talent> talentsForCategory(String category) {
        if (category == null || category.isBlank()) return List.of();
        return activeTalents.stream()
            .filter(t -> switch (category) {
                case "sport" -> t.sportBonus > 0;
                case "invest" -> t.investBonus > 0;
                case "art" -> t.artBonus > 0;
                case "craft" -> t.craftBonus > 0;
                case "travel" -> t.travelBonus > 0;
                default -> false;
            })
            .toList();
    }

    /** 类别中文标签 */
    static String categoryLabel(String cat) {
        if (cat == null) return "";
        return switch (cat) {
            case "sport" -> "运动";
            case "invest" -> "理财";
            case "art" -> "艺术";
            case "craft" -> "手工";
            case "travel" -> "旅行";
            default -> cat;
        };
    }

    /** 支出倍率：勤俭持家等天赋让财富扣减打折（多个取乘积） */
    private double getSpendRate() {
        double rate = 1.0;
        for (Talent t : activeTalents) { if (t.spendRate > 0 && t.spendRate < 1.0) rate *= t.spendRate; }
        return rate;
    }

    // ==================== 事件基调（坏/好事概率） ====================

    /** 负面事件概率加成总和（倒霉体质） */
    private double getBadEventRate() {
        double s = 0.0;
        for (Talent t : activeTalents) { if (t.badEventRate > 0) s += t.badEventRate; }
        return s;
    }

    /** 奇遇概率加成总和（天选之子） */
    private double getEventBonus() {
        double s = 0.0;
        for (Talent t : activeTalents) { if (t.eventBonus > 0) s += t.eventBonus; }
        return s;
    }

    /** 事件净效果（所有选项 eff 带符号和），用于推断缺省基调 */
    private int netEff(GameEvent e) {
        if (e.opts == null) return 0;
        int sum = 0;
        for (EventOpt o : e.opts) {
            if (o.eff == null) continue;
            for (String p : o.eff.split("\\s+")) {
                if (p.isEmpty()) continue;
                int sign = 1;
                String kv = p;
                if (p.contains("-") && !p.startsWith("-")) { sign = -1; kv = p.replace("-", "+"); }
                else if (p.startsWith("-")) { sign = -1; }
                String[] kvp = kv.split("[+=]");
                if (kvp.length != 2) continue;
                try { sum += sign * Integer.parseInt(kvp[1]); } catch (NumberFormatException ignored) {}
            }
        }
        return sum;
    }

    private boolean isBadEvent(GameEvent e) {
        if (e.tone != null && !e.tone.isEmpty()) return "bad".equals(e.tone);
        return netEff(e) < 0;
    }

    private boolean isGoodEvent(GameEvent e) {
        if (e.tone != null && !e.tone.isEmpty()) return "good".equals(e.tone);
        return netEff(e) > 0;
    }

    /** 事件选择加权：基础权重 1.0，坏事件加「倒霉体质」率、好事件加「天选之子」率 */
    private GameEvent pickRandomEvent(List<GameEvent> pool) {
        if (pool.isEmpty()) return null;
        if (pool.size() == 1) return pool.get(0);
        double badRate = getBadEventRate();
        double goodBonus = getEventBonus();
        double[] w = new double[pool.size()];
        double total = 0.0;
        for (int i = 0; i < pool.size(); i++) {
            GameEvent e = pool.get(i);
            double weight = 1.0;
            if (isBadEvent(e)) weight += badRate;
            else if (isGoodEvent(e)) weight += goodBonus;
            w[i] = weight;
            total += weight;
        }
        double r = rng.nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r < 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    /** 命中有类别加成天赋时的 AI 提示行（无则空串，不污染 prompt） */
    private String talentHintFor(GameEvent evt) {
        List<Talent> hitting = talentsForCategory(evt.category);
        if (hitting.isEmpty()) return "";
        String names = hitting.stream().map(t -> "『" + t.name + "』").collect(Collectors.joining("、"));
        return "\n【天赋加成】" + names + " 让他在" + categoryLabel(evt.category)
            + "上如鱼得水，事半功倍。";
    }

    private void applyTalents() {
        for (Talent t : activeTalents) {
            if (t.intel != 0) intel = cStd(intel+t.intel);
            if (t.physique != 0) physique = cStd(physique+t.physique);
            if (t.charm != 0) charm = cStd(charm+t.charm);
            if (t.wealth != 0) wealth = cStd(wealth+t.wealth);
            if (t.mood != 0) { mood = cStd(mood+t.mood); clampMood(); }
            if (t.health != 0) health = c(health+t.health);
            if (t.luck != 0) luck = cStd(luck+t.luck);
            if (t.morality != 0) morality = cStd(morality+t.morality);
            if (t.parentsRelation != 0) parentsRel = cStd(parentsRel+t.parentsRelation);
            if (t.partnerRelation != 0) partnerRel = cStd(partnerRel+t.partnerRelation); // 修复 partnerRelation 死代码
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
        sb.append(statCompactRich());
        if (!activeTalents.isEmpty()) {
            sb.append("\n🌟 ").append(activeTalents.stream().map(t->t.name).collect(Collectors.joining(" ")));
        }
        sb.append("\n\n📌 ").append(evt.desc).append("\n");
        if (evt.opts != null) {
            for (int i = 0; i < evt.opts.size(); i++) {
                EventOpt o = evt.opts.get(i);
                sb.append("\n").append(i+1).append(". ").append(o.text);
                if (o.needTalent != null && !o.needTalent.isEmpty()
                    || o.needTag != null && !o.needTag.isEmpty()) {
                    boolean locked = !o.availableTo(activeTalents);
                    String need = o.needDesc();
                    sb.append(locked ? " 🔒需" : " ⭐解锁")
                      .append("「").append(need != null ? need : "").append("」");
                }
            }
        }
        sb.append("\n\n💡 /state /help /restart");
        return sb.toString();
    }

    /** 按真实生效值渲染属性变化（含成长倍率/类别加成后的值），修复「显示+5实际+6」的问题 */
    private String effToEmoji(Map<String,Integer> delta) {
        if (delta == null || delta.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : delta.entrySet()) {
            int v = e.getValue();
            String icon = switch (e.getKey()) {
                case "intel"->"🧠"; case "physique"->"💪"; case "charm"->"💃";
                case "wealth"->"💰"; case "mood"->"😊"; case "health"->"❤️";
                case "luck"->"🍀"; case "morality"->"⚖️";
                case "parentsRelation"->"👨‍👩‍👧";
                default -> e.getKey();
            };
            sb.append(icon).append(v >= 0 ? "+" : "").append(v).append(" ");
        }
        return sb.toString().strip();
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

    /** 数值行 + 一句话状态小结（按数值给形容词），沿用 statCompact 的原始数字 */
    private String statCompactRich() {
        return String.format("🧠智力%d  💪体质%d  💃魅力%d\n💰财富%d  😊心情%d  ❤️健康%d\n✨状态：%s",
            intel, physique, charm, wealth, mood, health, statusSummary());
    }

    /** 按数值生成状态形容词小结：最强项 + 最弱项 */
    private String statusSummary() {
        List<String> words = new ArrayList<>();
        words.add(desc("intel", intel));
        words.add(desc("physique", physique));
        words.add(desc("charm", charm));
        words.add(desc("wealth", wealth));
        words.add(desc("mood", mood));
        words.add(desc("health", health));
        // 按数值档位排序，取最高档描述 + 最低档描述
        Comparator<String> byRank = Comparator.comparingInt(w -> rankOf(w));
        String best = words.stream().max(byRank).orElse("平平");
        String worst = words.stream().min(byRank).orElse("平平");
        if (best.equals(worst)) return best;
        return best + "，" + worst;
    }

    /** 状态词内部排序权重（档位越高越好） */
    private int rankOf(String w) {
        return switch (w) {
            case "头脑聪慧", "体魄健壮", "魅力四射", "家境殷实", "神采飞扬", "身康体健" -> 4;
            case "思维敏捷", "身强体健", "落落大方", "衣食无忧", "心情舒畅", "小有活力" -> 3;
            case "资质平平", "体质一般", "相貌平平", "手头略紧", "郁郁寡欢", "亚健康" -> 2;
            default -> 1;
        };
    }

    /** 单维形容词 */
    private String desc(String attr, int val) {
        boolean high = val >= 80, mid = val >= 60, low = val >= 40;
        return switch (attr) {
            case "intel" -> high ? "头脑聪慧" : mid ? "思维敏捷" : low ? "资质平平" : "反应迟缓";
            case "physique" -> high ? "体魄健壮" : mid ? "身强体健" : low ? "体质一般" : "体弱单薄";
            case "charm" -> high ? "魅力四射" : mid ? "落落大方" : low ? "相貌平平" : "其貌不扬";
            case "wealth" -> high ? "家境殷实" : mid ? "衣食无忧" : low ? "手头略紧" : "囊中羞涩";
            case "mood" -> high ? "神采飞扬" : mid ? "心情舒畅" : low ? "郁郁寡欢" : "心灰意冷";
            case "health" -> high ? "身康体健" : mid ? "小有活力" : low ? "亚健康" : "病痛缠身";
            default -> "平平";
        };
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
            .filter(s -> !s.isEmpty() && s.length() <= 9) // 防溢出，最多9位数
            .mapToInt(s -> { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; } })
            .filter(n -> n > 0).distinct().toArray();
    }

    private int c(int v) { return Math.max(0, Math.min(getHealthMax(), v)); }

    /** 标准属性钳制：上限固定 100（健康类用 c()，可被「铁打的身体」提高上限） */
    private int cStd(int v) { return Math.max(0, Math.min(100, v)); }

    /** 天赋加成后的健康上限（默认100，铁打的身体+10→110） */
    private int getHealthMax() {
        int max = 100;
        for (Talent t : activeTalents) { if (t.healthMax > 0) max = Math.max(max, t.healthMax); }
        return max;
    }

    /** 天赋加成后的心情下限（默认0，乐观性格→15） */
    private int getMoodMin() {
        int min = 0;
        for (Talent t : activeTalents) { if (t.moodMin > 0) min = Math.max(min, t.moodMin); }
        return min;
    }

    /** 心情下限钳制：乐观性格等天赋让心情不会掉到 moodMin 以下 */
    private void clampMood() {
        int min = getMoodMin();
        if (min > 0 && mood < min) mood = min;
    }

    @Override public boolean isOver() { return over; }
    @Override public String stateContext() { return name + " " + age + "岁 " + getStageLabel(); }

    // ==================== 调试钩子（验证/排查用，常驻无害） ====================

    /** 固定随机种子，使验证可复现 */
    public void debugSetSeed(long seed) { rng.setSeed(seed); }

    /** 强制指定开局天赋（跳过抽卡），供定向验证天赋效果 */
    public void debugForceTalents(String... talentNames) {
        drawnTalents.clear();
        activeTalents.clear();
        for (String tn : talentNames) {
            allTalents.stream().filter(t -> tn.equals(t.name) || tn.equals(t.id))
                .findFirst().ifPresent(t -> { drawnTalents.add(t); activeTalents.add(t); });
        }
        // 补足 drawnTalents 到至少 3 个（优先无加成白卡「普通人生」，避免干扰定向验证）
        if (drawnTalents.size() < 3) {
            // 优先找 id="w1" 的「普通人生」（纯白卡，无任何数值加成）
            allTalents.stream().filter(t -> "w1".equals(t.id) && !drawnTalents.contains(t))
                .findFirst().ifPresent(t -> { drawnTalents.add(t); activeTalents.add(t); });
            // 仍不足则按稀有度 white→blue 补
            for (Talent t : allTalents) {
                if (drawnTalents.size() >= 3) break;
                if (drawnTalents.contains(t)) continue;
                if ("white".equals(t.rarity) || "blue".equals(t.rarity)) {
                    drawnTalents.add(t); activeTalents.add(t);
                }
            }
        }
    }

    /** 当前完整状态快照（调试输出） */
    public String debugState() {
        return "age=" + age + " stage=" + stage + " phase=" + phase
            + " intel=" + intel + " physique=" + physique + " charm=" + charm
            + " wealth=" + wealth + " mood=" + mood + " health=" + health
            + " luck=" + luck + " morality=" + morality
            + " parentsRel=" + parentsRel + " partnerRel=" + partnerRel
            + " job=" + job + " talents=" + activeTalents.stream().map(t->t.name).toList();
    }

    // ==================== 数据类 ====================

    static class EventWrapper { List<GameEvent> events; }
    static class GameEvent {
        String id, stage, type, title, desc, reqTalent;
        String category;   // 事件类别：sport/invest/art/craft/travel，触发对应天赋加成
        String tone;       // 事件基调：bad/good，缺省按 netEff 正负推断
        String reqTag;     // 需要某个天赋标签才能出现（配合 Talent.tags）
        int minAge = -1, maxAge = 999;
        List<EventOpt> opts;
        boolean ageMatch(int a) { return a >= minAge && a <= maxAge; }
        boolean talentMatch(List<Talent> active) {
            // reqTalent：按天赋名或 id 精确匹配（原逻辑保留）
            if (reqTalent != null && !reqTalent.isEmpty()) {
                boolean byName = active.stream().anyMatch(t -> reqTalent.equals(t.name));
                boolean byId = active.stream().anyMatch(t -> reqTalent.equals(t.id));
                if (!byName && !byId) return false;
            }
            // reqTag：任一 active 天赋带该标签即匹配 —— 一个域事件服务多个天赋
            if (reqTag != null && !reqTag.isEmpty()) {
                return active.stream().anyMatch(t -> t.hasTag(reqTag));
            }
            return true;
        }
    }
    static class EventOpt {
        String text, eff, needTalent;
        String needTag;    // 需天赋标签的解锁选项（如 sport/invest/art）
        /** 该选项对当前天赋集合是否可选（认 needTalent 名/id 或 needTag 标签） */
        boolean availableTo(List<Talent> active) {
            if (active == null) return false;
            if (needTalent != null && !needTalent.isEmpty()) {
                boolean byName = active.stream().anyMatch(t -> needTalent.equals(t.name));
                boolean byId = active.stream().anyMatch(t -> needTalent.equals(t.id));
                if (!byName && !byId) return false;
            }
            if (needTag != null && !needTag.isEmpty()) {
                if (active.stream().noneMatch(t -> t.hasTag(needTag))) return false;
            }
            return true;
        }
        /** 解锁需求描述（用于 🔒 提示） */
        String needDesc() {
            if (needTalent != null && !needTalent.isEmpty()) return needTalent;
            if (needTag != null && !needTag.isEmpty()) return categoryLabel(needTag);
            return null;
        }
    }
    static class Talent {
        String id, name, rarity, desc;
        List<String> tags = new ArrayList<>(); // 天赋标签，配合事件 reqTag / 选项 needTag
        double intelRate=1.0, physiqueRate=1.0, charmRate=1.0, wealthRate=1.0, moodRate=1.0, healthRate=1.0;
        double relationRate=1.0, relationBonus=0.0, spendRate=1.0;
        int intel, physique, charm, wealth, mood, health, luck, morality;
        int parentsRelation, partnerRelation;
        double sportBonus, investBonus, artBonus, craftBonus, travelBonus, eventBonus, badEventRate;
        int moodMin;
        int healthMax;
        boolean hasTag(String tag) {
            return tag != null && tags != null && tags.contains(tag);
        }
    }
}
