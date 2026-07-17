package org.example.bot;

/**
 * AI 服务接口 — 所有 AI 实现（DeepSeek、OpenAI、Claude 等）都要实现此接口。
 *
 * <h3>设计目的</h3>
 * 让 AI 调用与微信 iLink 解耦。更换 AI 服务时，只需要写一个新的实现类，
 * 主程序（{@link WeChatBotApp}）一行代码都不需要改。
 *
 * <h3>如何接入新的 AI</h3>
 * <pre>{@code
 * public class OpenAiService implements AiService {
 *     public String chat(String userMessage) {
 *         // 调用 OpenAI API，返回回复文本
 *     }
 * }
 * }</pre>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * AiService ai = new DeepSeekAiService("你是一个友好的助手");  // 或任何实现类
 * String reply = ai.chat("你好");
 * }</pre>
 */
public interface AiService {

    /**
     * 接收用户消息，返回 AI 生成的回复文本。
     *
     * @param userMessage 用户发送的消息内容（纯文本）
     * @return AI 生成的回复（纯文本），如果调用失败也应返回友好的错误提示而不是抛异常
     */
    String chat(String userMessage);
}
