package org.example.bot.impl;

import org.example.bot.service.GarbageService;

import java.util.*;

/**
 * 垃圾分类查询实现 — 基于本地词典，无需 API Key。
 *
 * <p>覆盖 150+ 常见物品，支持模糊匹配（部分关键词命中）。
 * 分类标准：上海/北京等城市通用的四分类。
 */
public class GarbageServiceImpl implements GarbageService {

    /** 物品名 → 分类标签 */
    private static final Map<String, Category> DICT = buildDictionary();

    public GarbageServiceImpl() {
        System.out.println("[垃圾] 垃圾分类服务已就绪（" + DICT.size() + " 条物品）");
    }

    @Override
    public String classify(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return "请告诉我你想查什么物品，例如「电池」「奶茶杯」「旧衣服」～";
        }
        String name = itemName.strip();

        // 精确匹配
        Category cat = DICT.get(name);
        if (cat != null) {
            return format(name, cat);
        }

        // 去掉末尾的"类""等"等字再试
        String trimmed = name.replaceAll("[类等物]$", "");
        if (!trimmed.equals(name)) {
            cat = DICT.get(trimmed);
            if (cat != null) return format(trimmed, cat);
        }

        // 模糊匹配：看物品名包含哪些关键词
        Category best = null;
        int bestLen = 0;
        for (Map.Entry<String, Category> e : DICT.entrySet()) {
            String key = e.getKey();
            if (key.length() > bestLen && (name.contains(key) || key.contains(name))) {
                best = e.getValue();
                bestLen = key.length();
            }
        }

        if (best != null) {
            return format(name, best) + "\n💡 以上为模糊匹配结果，仅供参考。";
        }

        return "抱歉，暂时没查到「" + name + "」的分类。\n"
                + "💡 你可以试试换个说法，比如「剩饭」→「米饭」、「可乐罐」→「易拉罐」～\n"
                + "📋 小贴士：猪能吃的叫湿垃圾，猪不能吃的叫干垃圾，\n"
                + "猪吃了会死的叫有害垃圾，能卖钱买猪的叫可回收物。";
    }

    // ==================== 内部方法 ====================

    private String format(String name, Category cat) {
        StringBuilder sb = new StringBuilder();
        sb.append("🗑 「").append(name).append("」\n");
        sb.append("━━━━━━━━━━━━━━\n");
        sb.append("📦 分类：").append(cat.label).append("\n");
        sb.append("🎨 颜色：").append(cat.color).append("\n");
        sb.append("📝 说明：").append(cat.description).append("\n");
        sb.append("💡 投放：").append(cat.tip);
        return sb.toString();
    }

    // ==================== 分类定义 ====================

    private static class Category {
        final String label;
        final String color;
        final String description;
        final String tip;

        Category(String label, String color, String description, String tip) {
            this.label = label;
            this.color = color;
            this.description = description;
            this.tip = tip;
        }
    }

    private static final Category RECYCLABLE = new Category(
        "可回收物", "蓝色桶",
        "适宜回收、可循环利用的废弃物",
        "清洁干燥后投放到蓝色垃圾桶，纸箱压扁、瓶罐洗净"
    );

    private static final Category HAZARDOUS = new Category(
        "有害垃圾", "红色桶",
        "对人体健康或自然环境造成直接或潜在危害的废弃物",
        "轻放不破损，连带包装投入红色垃圾桶"
    );

    private static final Category FOOD_WASTE = new Category(
        "湿垃圾（厨余垃圾）", "棕色/绿色桶",
        "易腐烂的生物质生活废弃物",
        "沥干水分后投入棕色垃圾桶，塑料袋另投干垃圾"
    );

    private static final Category RESIDUAL = new Category(
        "干垃圾（其他垃圾）", "黑色桶",
        "除可回收物、有害垃圾、湿垃圾以外的其他生活废弃物",
        "尽量沥干水分后投入黑色垃圾桶"
    );

    // ==================== 词典（150+条）====================

    private static Map<String, Category> buildDictionary() {
        Map<String, Category> dict = new LinkedHashMap<>();

        // ---- 可回收物 ----
        String[] recyclable = {
            "报纸", "书本", "纸箱", "纸板", "信封", "宣传单", "名片", "纸袋",
            "塑料瓶", "塑料桶", "塑料盒", "塑料杯", "塑料餐具", "塑料玩具",
            "洗发水瓶", "沐浴露瓶", "洗洁精瓶", "洗衣液瓶", "化妆品瓶",
            "易拉罐", "铝罐", "铁罐", "金属罐", "罐头盒",
            "旧衣服", "旧鞋", "旧包", "旧帽子", "床单", "被套", "枕头", "毛绒玩具",
            "玻璃瓶", "玻璃杯", "玻璃罐", "酒瓶", "调料瓶", "化妆瓶",
            "废铁", "废铜", "废铝", "废钢", "铁丝", "铁钉", "螺丝", "钥匙",
            "旧手机", "旧电脑", "旧电视", "旧冰箱", "旧洗衣机", "旧空调",
            "电路板", "数据线", "充电器", "充电宝", "U盘", "鼠标", "键盘",
            "可乐罐", "矿泉水瓶", "饮料瓶", "牛奶盒", "酸奶盒",
            "旧杂志", "旧课本", "旧日历", "旧挂历", "旧笔记",
            "泡沫塑料", "泡沫箱", "泡沫板",
            "塑料衣架", "塑料盆", "塑料桶", "塑料凳",
            "旧自行车", "旧电动车", "旧玩具车",
        };
        for (String s : recyclable) dict.put(s, RECYCLABLE);

        // ---- 有害垃圾 ----
        String[] hazardous = {
            "电池", "充电电池", "纽扣电池", "锂电池", "铅酸电池", "蓄电池",
            "灯泡", "节能灯", "荧光灯", "日光灯管", "LED灯",
            "水银温度计", "体温计", "血压计",
            "废油漆", "废涂料", "油漆桶", "废胶水", "指甲油", "卸甲水",
            "过期药品", "过期药", "胶囊", "药片", "药水", "中药渣", "农药",
            "杀虫剂", "蚊香液", "樟脑丸", "老鼠药", "蟑螂药",
            "废相纸", "废胶片", "X光片", "CT片",
            "废机油", "废润滑油", "废柴油",
            "废墨盒", "硒鼓", "废碳粉盒",
            "废灯管", "废灯泡", "废节能灯",
            "消毒液", "消毒剂", "84消毒液", "漂白剂",
        };
        for (String s : hazardous) dict.put(s, HAZARDOUS);

        // ---- 湿垃圾（厨余垃圾）----
        String[] foodWaste = {
            "米饭", "面条", "馒头", "面包", "蛋糕", "饼干", "包子", "饺子",
            "剩饭", "剩菜", "菜叶", "菜根", "菜帮", "菜梗",
            "苹果核", "梨核", "桃核", "西瓜皮", "香蕉皮", "橘子皮", "橙子皮",
            "葡萄皮", "芒果核", "荔枝壳", "龙眼壳", "榴莲壳", "椰子壳",
            "鸡骨头", "鸭骨头", "鱼骨头", "排骨", "猪骨", "牛骨", "羊骨",
            "鸡蛋壳", "鸭蛋壳", "蛋壳",
            "茶叶渣", "咖啡渣", "枸杞", "菊花", "红枣核", "桂圆壳",
            "过期食品", "过期零食", "过期面包", "过期牛奶",
            "果皮", "果核", "果仁壳", "瓜子壳", "花生壳", "核桃壳",
            "剩菜剩饭", "汤渣", "火锅底料",
            "绿植", "花卉", "盆栽", "落叶", "枯枝", "干花", "鲜花",
            "中药渣", "药渣", "草药渣",
            "豆腐", "豆制品", "豆浆渣", "豆渣",
            "猫粮", "狗粮", "宠物食品",
        };
        for (String s : foodWaste) dict.put(s, FOOD_WASTE);

        // ---- 干垃圾（其他垃圾）----
        String[] residual = {
            "纸巾", "卫生纸", "餐巾纸", "湿纸巾", "面巾纸", "厨房纸",
            "尿不湿", "卫生巾", "护垫", "纸尿裤", "拉拉裤",
            "烟头", "烟蒂", "烟灰",
            "陶瓷碗", "陶瓷杯", "陶瓷盘", "花盆", "砂锅",
            "一次性筷子", "一次性餐盒", "一次性杯子", "一次性纸杯",
            "塑料袋", "保鲜膜", "保鲜袋", "气泡膜", "胶带",
            "口香糖", "泡泡糖",
            "头发", "指甲", "毛发",
            "创可贴", "棉签", "棉球", "纱布", "胶布",
            "灰土", "尘土", "炉渣", "煤灰",
            "大骨头", "硬贝壳", "螃蟹壳", "扇贝壳", "生蚝壳", "海螺壳",
            "猫砂", "宠物粪便", "宠物毛发",
            "橡皮泥", "太空沙", "水晶泥",
            "打火机", "火柴",
            "铅笔", "圆珠笔", "钢笔", "蜡笔", "水彩笔",
            "复写纸", "传真纸", "热敏纸", "收银小票", "快递单",
            "眼镜", "隐形眼镜", "眼镜盒", "眼镜布",
            "竹签", "牙签", "棒冰棍", "雪糕棍",
            "眼镜片", "镜子", "玻璃胶",
            "贴纸", "标签", "胶水", "胶棒",
            "口罩", "手套", "雨衣", "雨鞋",
            "尼龙绳", "麻绳", "编织袋", "蛇皮袋",
        };
        for (String s : residual) dict.put(s, RESIDUAL);

        return dict;
    }
}