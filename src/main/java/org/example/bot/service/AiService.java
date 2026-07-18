package org.example.bot.service;

import org.example.bot.model.DrawIntent;
import org.example.bot.model.WeatherIntent;

/**
 * AI 服务接口 — 所有 AI 对话实现（DeepSeek、OpenAI、Claude 等）都要实现此接口。
 *
 * <h3>如何接入新的 AI</h3>
 * <pre>{@code
 * public class OpenAiServiceImpl implements AiService {
 *     public String chat(String userMessage) { ... }
 *     public DrawIntent extractDrawIntent(String userMessage) { ... }
 *     public WeatherIntent extractWeatherIntent(String userMessage) { ... }
 * }
 * }</pre>
 */
public interface AiService {

    /**
     * 接收用户消息，返回 AI 生成的回复文本。
     *
     * @param userMessage 用户发送的消息内容（纯文本）
     * @return AI 生成的回复
     */
    String chat(String userMessage);

    /**
     * 分析用户消息，判断是否想生成图片，并提取主题描述。
     *
     * <p>这是一次独立的轻量 AI 调用，不消耗对话历史。
     * 失败时返回 {@code DrawIntent.notDraw()}，主程序自动降级为普通对话。
     *
     * @param userMessage 用户发送的消息内容
     * @return 意图识别结果，包含 isDraw 和 prompt
     */
    DrawIntent extractDrawIntent(String userMessage);

    /**
     * 分析用户消息，判断是否在查询天气，并提取城市名。
     *
     * <p>这是一次独立的轻量 AI 调用，不消耗对话历史。
     * 失败时返回 {@code WeatherIntent.notWeather()}，主程序自动降级为普通对话。
     *
     * @param userMessage 用户发送的消息内容
     * @return 意图识别结果，包含 isWeather 和 city
     */
    WeatherIntent extractWeatherIntent(String userMessage);
}
