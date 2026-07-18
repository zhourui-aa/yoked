package org.example.bot.model;

/**
 * 生图意图提取结果 — 由 AI 分析用户消息后返回。
 *
 * @param isDraw 用户是否想要生成图片
 * @param prompt AI 提取出的生图主题描述（isDraw=false 时为 null）
 */
public class DrawIntent {
    private final boolean isDraw;
    private final String prompt;

    public DrawIntent(boolean isDraw, String prompt) {
        this.isDraw = isDraw;
        this.prompt = prompt;
    }

    /** 快速创建一个"非生图"结果 */
    public static DrawIntent notDraw() {
        return new DrawIntent(false, null);
    }

    /** 快速创建一个生图结果 */
    public static DrawIntent draw(String prompt) {
        return new DrawIntent(true, prompt);
    }

    public boolean isDraw() { return isDraw; }

    /** 生图主题描述，isDraw 为 false 时返回 null */
    public String prompt() { return prompt; }

    /** prompt 为空或仅含空白（isDraw=true 但没有有效描述） */
    public boolean promptIsEmpty() {
        return prompt == null || prompt.isBlank();
    }
}
