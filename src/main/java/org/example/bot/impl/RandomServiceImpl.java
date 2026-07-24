package org.example.bot.impl;

import org.example.bot.service.RandomService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机工具服务实现 — 掷骰、随机数、抽签、抛硬币。
 */
public class RandomServiceImpl implements RandomService {

    private static final int MAX_DICE_COUNT = 100;
    private static final int MAX_DICE_SIDES = 1000;
    private static final long MAX_RANGE = 1_000_000_000L;

    public RandomServiceImpl() {
        System.out.println("[随机] 随机工具服务已就绪");
    }

    @Override
    public String rollDice(int count, int sides) {
        if (count < 1 || count > MAX_DICE_COUNT) {
            return "骰子个数需在 1~" + MAX_DICE_COUNT + " 之间。";
        }
        if (sides < 2 || sides > MAX_DICE_SIDES) {
            return "骰子面数需在 2~" + MAX_DICE_SIDES + " 之间。";
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Integer> rolls = new ArrayList<>(count);
        int total = 0;
        for (int i = 0; i < count; i++) {
            int value = random.nextInt(1, sides + 1);
            rolls.add(value);
            total += value;
        }

        if (count == 1) {
            return String.format("🎲 掷出了 %d 点（%d 面骰）", rolls.get(0), sides);
        }
        return String.format("🎲 掷 %d 个 %d 面骰：%s，合计 %d 点",
                count, sides, rolls, total);
    }

    @Override
    public String randomInt(int min, int max) {
        if (min > max) {
            return "最小值不能大于最大值。";
        }
        long range = (long) max - min + 1;
        if (range > MAX_RANGE) {
            return "随机范围过大，请缩小 min 和 max 的差值。";
        }

        int value = ThreadLocalRandom.current().nextInt(min, max + 1);
        return String.format("🎯 随机数：%d（范围 %d ~ %d）", value, min, max);
    }

    @Override
    public String randomChoice(List<String> options) {
        if (options == null || options.isEmpty()) {
            return "请提供至少一个选项。";
        }

        List<String> cleaned = new ArrayList<>();
        for (String option : options) {
            if (option != null && !option.isBlank()) {
                cleaned.add(option.strip());
            }
        }
        if (cleaned.isEmpty()) {
            return "选项不能为空。";
        }
        if (cleaned.size() == 1) {
            return "只有一个选项「" + cleaned.get(0) + "」，无需随机。";
        }

        String picked = cleaned.get(ThreadLocalRandom.current().nextInt(cleaned.size()));
        return String.format("🎁 抽中了：「%s」（共 %d 个选项）", picked, cleaned.size());
    }

    @Override
    public String flipCoin() {
        boolean heads = ThreadLocalRandom.current().nextBoolean();
        return heads ? "🪙 正面" : "🪙 反面";
    }
}
