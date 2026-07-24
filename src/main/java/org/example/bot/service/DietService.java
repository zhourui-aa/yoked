package org.example.bot.service;

/**
 * 饮食推荐服务接口 — 根据身高、体重、目标给出个性化饮食方案。
 *
 * <p>计算基于 Mifflin-St Jeor 公式估算 BMR，结合活动系数和目标
 * 自动计算每日热量和三大营养素配比。
 */
public interface DietService {

    /**
     * 生成个性化饮食推荐方案。
     *
     * @param heightCm 身高（厘米），范围 130-250
     * @param weightKg 体重（公斤），范围 30-300
     * @param goal     目标：减脂 / 增肌
     * @return 格式化的饮食推荐文本（含热量、营养素、饮食建议）
     */
    String getRecommendation(int heightCm, double weightKg, String goal);
}
