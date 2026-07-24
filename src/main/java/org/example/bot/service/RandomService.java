package org.example.bot.service;

import java.util.List;

/**
 * 随机工具服务接口 — 掷骰、随机数、抽签、抛硬币。
 */
public interface RandomService {

    /**
     * 掷骰子。
     *
     * @param count 骰子个数
     * @param sides 每个骰子的面数
     * @return 格式化的掷骰结果
     */
    String rollDice(int count, int sides);

    /**
     * 生成指定范围内的随机整数（含上下界）。
     *
     * @param min 最小值（含）
     * @param max 最大值（含）
     * @return 格式化的随机数结果
     */
    String randomInt(int min, int max);

    /**
     * 从多个选项中随机抽取一个。
     *
     * @param options 选项列表
     * @return 格式化的抽取结果
     */
    String randomChoice(List<String> options);

    /**
     * 抛硬币。
     *
     * @return 正面或反面
     */
    String flipCoin();
}
