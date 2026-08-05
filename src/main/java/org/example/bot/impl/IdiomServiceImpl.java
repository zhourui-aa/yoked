package org.example.bot.impl;

import org.example.bot.service.IdiomService;

import java.util.*;

/**
 * 成语接龙游戏实现 — 机器人当裁判并参与游戏。
 *
 * <p>内置 300+ 常用成语词典，按首字索引实现快速查找。
 * 规则：说出的成语首字必须与上一个成语的尾字相同，且不能重复。
 */
public class IdiomServiceImpl implements IdiomService {

    /** 按首字索引的成语词典 */
    private static final Map<Character, List<String>> DICT = buildDictionary();

    /** 每用户一局游戏的状态 */
    private final Map<String, GameState> games = new HashMap<>();

    private final Random random = new Random();

    public IdiomServiceImpl() {
        System.out.println("[成语] 成语接龙服务已就绪（" + DICT.size() + " 个首字，" + countAll() + " 条成语）");
    }

    // ==================== 对外接口 ====================

    @Override
    public String startGame(String userId) {
        GameState state = new GameState();
        games.put(userId, state);

        // 机器人随机选一个成语开局
        String first = randomIdiom(null);
        state.used.add(first);
        state.lastIdiom = first;

        return "🎯 成语接龙开始！\n"
                + "━━━━━━━━━━━━━━━\n"
                + "规则：接上一个成语的尾字，不能重复，不能自创。\n"
                + "说出「认输」或「换一个」可以跳过。\n"
                + "━━━━━━━━━━━━━━━\n"
                + "🤖 我先来：" + first + "\n"
                + "请接「" + lastChar(first) + "」开头的成语～";
    }

    @Override
    public String play(String userId, String idiom) {
        GameState state = games.get(userId);
        if (state == null) {
            return "还没有开始游戏哦，发送「成语接龙」开始一局吧！";
        }

        if (idiom == null || idiom.isBlank()) {
            return "请说出一个成语来接哦～";
        }
        idiom = idiom.strip();

        // 检查特殊指令
        if (idiom.equals("认输") || idiom.equals("我认输") || idiom.equals("放弃")) {
            return giveUp(userId);
        }
        if (idiom.equals("换一个") || idiom.equals("换个") || idiom.equals("跳过")) {
            return skip(userId, state);
        }

        // 长度检查
        if (idiom.length() != 4) {
            return "❌「" + idiom + "」不是四字成语哦，成语接龙需要四个字～";
        }

        // 首字检查
        char expected = lastChar(state.lastIdiom);
        char actual = idiom.charAt(0);
        if (actual != expected) {
            return "❌ 不对哦！上一个成语是「" + state.lastIdiom + "」，\n"
                    + "你需要接「" + expected + "」开头的成语，而不是「" + actual + "」～";
        }

        // 重复检查
        if (state.used.contains(idiom)) {
            return "❌「" + idiom + "」已经用过了，不能重复哦～";
        }

        // 查词典
        if (!isIdiom(idiom)) {
            return "❌「" + idiom + "」似乎不是常见成语，换一个试试？";
        }

        // 接龙成功！
        state.used.add(idiom);
        state.lastIdiom = idiom;
        state.score++;

        // 机器人接下一个
        char next = lastChar(idiom);
        List<String> candidates = DICT.get(next);
        String robotReply = null;
        if (candidates != null) {
            List<String> valid = new ArrayList<>();
            for (String c : candidates) {
                if (!state.used.contains(c)) {
                    valid.add(c);
                }
            }
            if (!valid.isEmpty()) {
                robotReply = valid.get(random.nextInt(valid.size()));
                state.used.add(robotReply);
                state.lastIdiom = robotReply;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("✅ 接得好！「").append(idiom).append("」\n");
        sb.append("📊 已接 ").append(state.score).append(" 个成语\n");

        if (robotReply != null) {
            sb.append("🤖 我接：").append(robotReply).append("\n");
            sb.append("请接「").append(lastChar(robotReply)).append("」开头的成语～");
        } else {
            sb.append("🎉 我接不上了，你赢啦！\n");
            sb.append("（「").append(lastChar(idiom)).append("」开头的成语太难了）");
            games.remove(userId);
        }

        return sb.toString();
    }

    @Override
    public String getState(String userId) {
        GameState state = games.get(userId);
        if (state == null) {
            return "当前没有进行中的成语接龙游戏，发送「成语接龙」开始吧！";
        }
        return "🎯 成语接龙进行中\n"
                + "已接 " + state.score + " 个成语\n"
                + "上一个：" + state.lastIdiom + "\n"
                + "请接「" + lastChar(state.lastIdiom) + "」开头的成语～";
    }

    @Override
    public String giveUp(String userId) {
        GameState state = games.get(userId);
        if (state == null) {
            return "还没有开始游戏呢～";
        }

        char last = lastChar(state.lastIdiom);
        List<String> candidates = DICT.get(last);
        StringBuilder sb = new StringBuilder();

        sb.append("😅 你认输啦！\n");
        sb.append("上一轮：").append(state.lastIdiom).append("\n");
        sb.append("共接了 ").append(state.score).append(" 个成语\n");

        if (candidates != null && !candidates.isEmpty()) {
            List<String> unused = new ArrayList<>();
            for (String c : candidates) {
                if (!state.used.contains(c)) {
                    unused.add(c);
                }
            }
            if (!unused.isEmpty()) {
                sb.append("💡「").append(last).append("」开头的成语有：");
                for (int i = 0; i < Math.min(5, unused.size()); i++) {
                    sb.append(unused.get(i));
                    if (i < Math.min(5, unused.size()) - 1) sb.append("、");
                }
                if (unused.size() > 5) sb.append(" 等");
                sb.append("\n");
            }
        }

        sb.append("🤖 下次加油！发送「成语接龙」再来一局～");
        games.remove(userId);
        return sb.toString();
    }

    // ==================== 内部方法 ====================

    private String skip(String userId, GameState state) {
        char last = lastChar(state.lastIdiom);
        List<String> candidates = DICT.get(last);
        if (candidates == null) {
            games.remove(userId);
            return "🤖 我也接不上了，这局算平局！\n发送「成语接龙」再来一局～";
        }
        List<String> unused = new ArrayList<>();
        for (String c : candidates) {
            if (!state.used.contains(c)) {
                unused.add(c);
            }
        }
        if (unused.isEmpty()) {
            games.remove(userId);
            return "🤖 我也接不上了，这局算平局！\n发送「成语接龙」再来一局～";
        }
        String robotReply = unused.get(random.nextInt(unused.size()));
        state.used.add(robotReply);
        state.lastIdiom = robotReply;
        return "🤖 我帮你接：" + robotReply + "\n"
                + "请接「" + lastChar(robotReply) + "」开头的成语～";
    }

    /** 随机选一个成语，可指定首字 */
    private String randomIdiom(Character firstChar) {
        List<String> pool;
        if (firstChar != null) {
            pool = DICT.get(firstChar);
            if (pool == null || pool.isEmpty()) {
                pool = allIdioms();
            }
        } else {
            pool = allIdioms();
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private List<String> allIdioms() {
        List<String> all = new ArrayList<>();
        for (List<String> list : DICT.values()) {
            all.addAll(list);
        }
        return all;
    }

    private boolean isIdiom(String idiom) {
        char first = idiom.charAt(0);
        List<String> list = DICT.get(first);
        return list != null && list.contains(idiom);
    }

    private char lastChar(String idiom) {
        return idiom.charAt(idiom.length() - 1);
    }

    private int countAll() {
        int count = 0;
        for (List<String> list : DICT.values()) {
            count += list.size();
        }
        return count;
    }

    // ==================== 游戏状态 ====================

    private static class GameState {
        String lastIdiom;
        final Set<String> used = new LinkedHashSet<>();
        int score;
    }

    // ==================== 成语词典（300+条，按首字索引）====================

    private static Map<Character, List<String>> buildDictionary() {
        Map<Character, List<String>> dict = new HashMap<>();

        add(dict, "一马当先", "一心一意", "一鸣惊人", "一帆风顺", "一举两得", "一箭双雕",
                "一见如故", "一诺千金", "一尘不染", "一往无前", "一针见血", "一目了然",
                "一落千丈", "一蹶不振", "一毛不拔", "一触即发", "一败涂地", "一窍不通",
                "一视同仁", "一丝不苟", "一言九鼎", "一叶知秋", "一劳永逸", "一呼百应");

        add(dict, "先发制人", "先入为主", "先见之明", "先斩后奏", "先声夺人");
        add(dict, "人山人海", "人尽皆知", "人才济济", "人言可畏", "人心所向",
                "人定胜天", "人面桃花", "人声鼎沸", "人满为患");
        add(dict, "海阔天空", "海纳百川", "海底捞月", "海市蜃楼", "海枯石烂");
        add(dict, "空前绝后", "空穴来风", "空中楼阁", "空口无凭", "空空如也");
        add(dict, "后来居上", "后生可畏", "后顾之忧", "后起之秀", "后悔莫及");
        add(dict, "上善若水", "上行下效", "上下一心", "上天入地");
        add(dict, "水落石出", "水到渠成", "水滴石穿", "水深火热", "水泄不通",
                "水涨船高", "水乳交融", "水木清华");
        add(dict, "天马行空", "天长地久", "天翻地覆", "天经地义", "天衣无缝",
                "天花乱坠", "天南海北", "天下太平", "天罗地网", "天涯海角", "天伦之乐");
        add(dict, "心旷神怡", "心想事成", "心口如一", "心花怒放", "心照不宣",
                "心血来潮", "心猿意马", "心悦诚服", "心领神会", "心灰意冷");
        add(dict, "高瞻远瞩", "高枕无忧", "高山流水", "高谈阔论", "高朋满座", "高不可攀");
        add(dict, "大公无私", "大器晚成", "大智若愚", "大快人心", "大相径庭",
                "大名鼎鼎", "大义灭亲", "大功告成", "大材小用", "大言不惭");
        add(dict, "万紫千红", "万众一心", "万象更新", "万无一失", "万马奔腾", "万寿无疆");
        add(dict, "千锤百炼", "千变万化", "千钧一发", "千里迢迢", "千方百计",
                "千丝万缕", "千载难逢", "千姿百态", "千军万马", "千差万别");
        add(dict, "百折不挠", "百发百中", "百战百胜", "百感交集",
                "百步穿杨", "百无聊赖", "百年好合");
        add(dict, "不约而同", "不可思议", "不劳而获", "不翼而飞", "不屈不挠",
                "不耻下问", "不知所措", "不假思索", "不言而喻", "不寒而栗",
                "不卑不亢", "不辞而别", "不欢而散", "不胫而走", "不拘一格",
                "不可救药", "不落窠臼", "不谋而合", "不求甚解");
        add(dict, "马到成功", "马不停蹄", "马革裹尸", "马首是瞻");
        add(dict, "龙飞凤舞", "龙马精神", "龙腾虎跃", "龙争虎斗", "龙潭虎穴");
        add(dict, "虎头蛇尾", "虎视眈眈", "虎口余生", "虎背熊腰", "虎落平阳");
        add(dict, "风调雨顺", "风起云涌", "风花雪月", "风和日丽", "风平浪静",
                "风驰电掣", "风雨同舟", "风卷残云", "风声鹤唳");
        add(dict, "花好月圆", "花言巧语", "花团锦簇", "花枝招展", "花天酒地");
        add(dict, "月明星稀", "月下老人", "月黑风高", "月满则亏");
        add(dict, "金碧辉煌", "金口玉言", "金榜题名", "金玉良言", "金蝉脱壳",
                "金戈铁马", "金屋藏娇");
        add(dict, "山清水秀", "山穷水尽", "山河壮丽", "山盟海誓", "山高水长", "山珍海味");
        add(dict, "明察秋毫", "明辨是非", "明枪暗箭", "明珠暗投", "明知故犯",
                "明哲保身", "明目张胆", "明镜高悬");
        add(dict, "日新月异", "日积月累", "日薄西山", "日月如梭", "日理万机");
        add(dict, "自以为是", "自相矛盾", "自作自受", "自强不息", "自知之明",
                "自告奋勇", "自食其力", "自投罗网", "自命不凡", "自娱自乐");
        add(dict, "有目共睹", "有口皆碑", "有恃无恐", "有的放矢", "有备无患",
                "有始有终", "有声有色", "有条不紊", "有教无类", "有板有眼");
        add(dict, "无所不能", "无微不至", "无与伦比", "无中生有", "无独有偶",
                "无济于事", "无动于衷", "无孔不入", "无价之宝", "无懈可击",
                "无地自容", "无可奈何", "无的放矢", "无边无际");

        add(dict, "三心二意", "三思而行", "三令五申", "三生有幸", "三顾茅庐",
                "三番五次", "三长两短", "三教九流");
        add(dict, "四面楚歌", "四海为家", "四通八达", "四平八稳", "四面八方");
        add(dict, "五光十色", "五颜六色", "五体投地", "五花八门", "五湖四海",
                "五谷丰登", "五雷轰顶");
        add(dict, "六神无主", "六亲不认", "六畜兴旺");
        add(dict, "七上八下", "七嘴八舌", "七手八脚", "七零八落", "七拼八凑");
        add(dict, "八面玲珑", "八仙过海", "八面威风", "八拜之交");
        add(dict, "九牛一毛", "九死一生", "九霄云外", "九牛二虎");
        add(dict, "十全十美", "十拿九稳", "十万火急", "十指连心", "十面埋伏");

        add(dict, "画龙点睛", "画蛇添足", "画饼充饥", "画地为牢");
        add(dict, "开门见山", "开天辟地", "开卷有益", "开门揖盗", "开诚布公",
                "开宗明义", "开源节流");
        add(dict, "对牛弹琴", "对症下药", "对答如流", "对酒当歌");
        add(dict, "出人头地", "出口成章", "出类拔萃", "出奇制胜", "出生入死",
                "出尔反尔", "出神入化", "出其不意");
        add(dict, "入木三分", "入乡随俗", "入不敷出");
        add(dict, "生龙活虎", "生机勃勃", "生灵涂炭", "生吞活剥", "生离死别",
                "生死攸关", "生花妙笔");
        add(dict, "死里逃生", "死心塌地", "死灰复燃", "死不足惜", "死不瞑目");
        add(dict, "地大物博", "地久天长", "地动山摇", "地广人稀");
        add(dict, "博古通今", "博大精深", "博学多才", "博览群书", "博采众长");
        add(dict, "今非昔比");
        add(dict, "比翼双飞", "比比皆是", "比肩接踵");
        add(dict, "飞黄腾达", "飞蛾扑火", "飞沙走石", "飞檐走壁", "飞扬跋扈");

        add(dict, "口是心非", "口若悬河", "口蜜腹剑", "口诛笔伐", "口口声声");
        add(dict, "非同小可", "非亲非故", "非此即彼", "非驴非马");
        add(dict, "成竹在胸", "成家立业", "成年累月", "成千上万", "成人之美");
        add(dict, "胸有成竹", "胸无城府", "胸怀大志");
        add(dict, "竹报平安", "竹篮打水");
        add(dict, "安分守己", "安居乐业", "安然无恙", "安之若素", "安步当车");
        add(dict, "己所不欲", "己饥己溺");
        add(dict, "欲速不达", "欲盖弥彰", "欲罢不能", "欲擒故纵");
        add(dict, "达官贵人");
        add(dict, "利令智昏", "利欲熏心", "利国利民");
        add(dict, "志同道合", "志在四方", "志得意满", "志大才疏");
        add(dict, "合情合理", "合而为一", "合浦还珠");

        add(dict, "气吞山河", "气壮山河", "气象万千", "气势磅礴", "气宇轩昂");
        add(dict, "竭尽全力", "竭泽而渔");
        add(dict, "力挽狂澜", "力排众议", "力所能及", "力透纸背", "力不从心");
        add(dict, "火中取栗", "火上浇油", "火眼金睛", "火冒三丈", "火烧眉毛");
        add(dict, "栗栗危惧");
        add(dict, "惧内怕外");
        add(dict, "外强中干", "外圆内方", "外柔内刚");
        add(dict, "干净利落", "干云蔽日");
        add(dict, "落花流水", "落井下石", "落落大方", "落叶归根");
        add(dict, "石破天惊", "石沉大海", "石火电光");
        add(dict, "惊弓之鸟", "惊天动地", "惊心动魄", "惊世骇俗");

        add(dict, "鸟语花香", "鸟尽弓藏");
        add(dict, "藏龙卧虎", "藏头露尾", "藏污纳垢");
        add(dict, "尾大不掉", "尾生抱柱");
        add(dict, "掉以轻心", "掉头不顾");
        add(dict, "顾此失彼", "顾名思义", "顾全大局", "顾影自怜");
        add(dict, "盈千累万", "盈则必亏");
        add(dict, "亏于一篑");

        add(dict, "知难而退", "知书达理", "知己知彼", "知恩图报", "知无不言");
        add(dict, "退避三舍");
        add(dict, "舍己为人", "舍生取义", "舍本逐末", "舍近求远");
        add(dict, "为人师表", "为所欲为", "为虎作伥", "为民请命");
        add(dict, "表里如一", "表里山河");
        add(dict, "如鱼得水", "如虎添翼", "如火如荼", "如释重负", "如雷贯耳",
                "如梦初醒", "如饥似渴", "如坐针毡");
        add(dict, "翼展宏图");

        add(dict, "图穷匕见", "图谋不轨");
        add(dict, "见义勇为", "见异思迁", "见缝插针", "见微知著", "见仁见智",
                "见猎心喜", "见好就收");
        add(dict, "迁怒于人", "迁客骚人");
        add(dict, "针锋相对");
        add(dict, "著作等身", "著书立说");
        add(dict, "身先士卒", "身体力行", "身临其境", "身败名裂", "身经百战");
        add(dict, "说三道四", "说长道短", "说一不二");

        add(dict, "行云流水", "行尸走肉", "行将就木", "行色匆匆", "行成于思");
        add(dict, "肉袒负荆", "肉眼凡胎");
        add(dict, "木已成舟", "木本水源", "木人石心");
        add(dict, "舟车劳顿", "舟中敌国");
        add(dict, "困兽犹斗", "困知勉行");
        add(dict, "斗争昂扬", "斗志昂扬");
        add(dict, "扬眉吐气", "扬长避短", "扬长而去", "扬汤止沸");

        add(dict, "羊肠小道", "羊质虎皮", "羊入虎口");
        add(dict, "道听途说", "道貌岸然", "道不拾遗");
        add(dict, "说长道短", "说一不二", "说三道四");
        add(dict, "短兵相接", "短小精悍");
        add(dict, "接二连三", "接踵而至", "接踵而来");
        add(dict, "至理名言", "至高无上", "至死不渝");
        add(dict, "言简意赅", "言过其实", "言不由衷", "言传身教", "言归正传", "言听计从");
        add(dict, "传宗接代", "传为佳话", "传道授业");

        add(dict, "老当益壮", "老生常谈", "老马识途", "老谋深算", "老成持重");
        add(dict, "壮心不已", "壮志凌云", "壮志未酬");
        add(dict, "谈笑风生", "谈虎色变", "谈何容易");
        add(dict, "变本加厉", "变幻莫测", "变废为宝");
        add(dict, "厉行节约", "厉兵秣马");
        add(dict, "马到成功", "马不停蹄", "马革裹尸");
        add(dict, "功德无量", "功亏一篑", "功成名就", "功败垂成");

        return dict;
    }

    private static void add(Map<Character, List<String>> dict, String... idioms) {
        for (String idiom : idioms) {
            char first = idiom.charAt(0);
            dict.computeIfAbsent(first, k -> new ArrayList<>()).add(idiom);
        }
    }
}