package org.example.bot.impl;

import org.example.bot.service.DietService;

/**
 * 饮食推荐服务实现 — 基于 Mifflin-St Jeor 公式的营养计算。
 *
 * <p>计算流程：身高+体重+目标 → BMR → TDEE → 目标热量 → 三大营养素配比 → 饮食建议
 */
public class DietServiceImpl implements DietService {

    /** 默认活动系数（中等活动量：每周运动 3-5 天） */
    private static final double ACTIVITY_FACTOR = 1.55;

    /** 减脂热量缺口 */
    private static final int CUT_DEFICIT = 500;
    /** 增肌热量盈余 */
    private static final int BULK_SURPLUS = 400;

    public DietServiceImpl() {
        System.out.println("[饮食] 饮食推荐服务已就绪");
    }

    @Override
    public String getRecommendation(int heightCm, double weightKg, String goal) {
        // 解析目标
        boolean isCut = goal.contains("减脂") || goal.contains("减重")
                     || goal.contains("瘦") || goal.contains("刷脂")
                     || goal.contains("cut") || goal.contains("Cut");

        // 计算 BMI
        double heightM = heightCm / 100.0;
        double bmi = weightKg / (heightM * heightM);

        // 估算 BMR（Mifflin-St Jeor 男女平均）
        // 男: 10w + 6.25h - 5a + 5, 女: 10w + 6.25h - 5a - 161
        // 平均: 10w + 6.25h - 5a - 78，假设年龄 25
        double bmr = 10 * weightKg + 6.25 * heightCm - 5 * 25 - 78;

        // TDEE
        double tdee = bmr * ACTIVITY_FACTOR;

        // 目标热量
        double targetKcal = isCut ? tdee - CUT_DEFICIT : tdee + BULK_SURPLUS;

        // 营养素配比
        double proteinG, fatG, carbG;
        if (isCut) {
            // 减脂：高蛋白 40%，中脂肪 25%，低碳水 35%
            proteinG = (targetKcal * 0.40) / 4;
            fatG     = (targetKcal * 0.25) / 9;
            carbG    = (targetKcal * 0.35) / 4;
        } else {
            // 增肌：高蛋白 30%，中脂肪 20%，高碳水 50%
            proteinG = (targetKcal * 0.30) / 4;
            fatG     = (targetKcal * 0.20) / 9;
            carbG    = (targetKcal * 0.50) / 4;
        }

        // 构建输出
        StringBuilder sb = new StringBuilder();
        sb.append("🍽 **个性化饮食推荐**\n\n");

        sb.append("📊 **身体数据**\n");
        sb.append(String.format("身高：%d cm  体重：%.1f kg  BMI：%.1f（%s）\n",
                heightCm, weightKg, bmi, bmiLabel(bmi)));
        sb.append(String.format("目标：%s\n\n", isCut ? "🔥 减脂" : "💪 增肌"));

        sb.append("⚡ **热量配置**\n");
        sb.append(String.format("基础代谢 (BMR)：%.0f kcal\n", bmr));
        sb.append(String.format("日常消耗 (TDEE)：%.0f kcal\n", tdee));
        sb.append(String.format("👉 目标摄入：**%.0f kcal/天**\n\n", targetKcal));

        sb.append("🥩 **每日营养素**\n");
        sb.append(String.format("蛋白质：%.0f g（%.0f kcal）\n", proteinG, proteinG * 4));
        sb.append(String.format("脂肪：  %.0f g（%.0f kcal）\n", fatG, fatG * 9));
        sb.append(String.format("碳水：  %.0f g（%.0f kcal）\n\n", carbG, carbG * 4));

        sb.append("📋 **饮食建议**\n");
        if (isCut) {
            sb.append("• 早餐：2个鸡蛋 + 全麦面包 + 牛奶\n");
            sb.append("• 午餐：鸡胸肉 150g + 杂粮饭 + 蔬菜\n");
            sb.append("• 加餐：希腊酸奶 / 蛋白粉\n");
            sb.append("• 晚餐：鱼肉/虾 150g + 大量绿叶蔬菜\n");
            sb.append("• 每天喝 2-3L 水，避免含糖饮料\n");
            sb.append("• 热量缺口约 500 kcal，预计每周减 0.5 kg\n");
        } else {
            sb.append("• 早餐：燕麦 + 3个鸡蛋 + 香蕉 + 牛奶\n");
            sb.append("• 午餐：牛肉/鸡腿 200g + 米饭 300g + 蔬菜\n");
            sb.append("• 加餐：坚果 + 全麦面包 + 花生酱\n");
            sb.append("• 晚餐：三文鱼/鸡胸 200g + 红薯 + 西兰花\n");
            sb.append("• 训练后 30 分钟内补充碳水+蛋白质\n");
            sb.append("• 热量盈余约 400 kcal，配合力量训练增肌\n");
        }

        sb.append("\n⚠️ 以上为通用建议，如需精准方案请咨询营养师。");
        return sb.toString();
    }

    private String bmiLabel(double bmi) {
        if (bmi < 18.5) return "偏瘦";
        if (bmi < 24)   return "正常";
        if (bmi < 28)   return "偏胖";
        return "肥胖";
    }
}
