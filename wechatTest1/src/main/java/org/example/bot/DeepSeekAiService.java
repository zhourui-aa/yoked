package org.example.bot;

import org.example.deepseek.DeepSeekClient;
import org.example.deepseek.config.DeepSeekConfig;
import org.example.deepseek.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek AI 服务实现。
 *
 * <p>实现 {@link AiService} 接口，内部使用已有的 {@link DeepSeekClient}
 * 调用 DeepSeek API 生成回复。
 *
 * <h3>功能特性</h3>
 * <ul>
 *   <li><b>对话历史</b>：自动保留最近 10 轮对话（20 条消息），让 AI 有上下文记忆</li>
 *   <li><b>系统提示词</b>：可以设定 AI 的角色和行为（例如"你是一个友好的微信助手"）</li>
 *   <li><b>容错处理</b>：API 调用失败时返回友好提示，而不是让整个程序崩溃</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * AiService ai = new DeepSeekAiService("你是一个友好的助手，请用中文回复。");
 * String reply = ai.chat("今天天气怎么样？");
 * }</pre>
 *
 * <h3>接入其他 AI 的步骤</h3>
 * <ol>
 *   <li>写一个新类实现 {@link AiService} 接口</li>
 *   <li>在 {@code WeChatBotApp.main()} 中把 {@code new DeepSeekAiService(...)}
 *       替换为你的实现类</li>
 *   <li>其他代码一行都不用改</li>
 * </ol>
 *
 * <h3>前置条件</h3>
 * 必须设置环境变量 {@code DEEPSEEK_API_KEY}，例如：
 * <pre>{@code set DEEPSEEK_API_KEY=sk-xxxx}</pre>
 */
public class DeepSeekAiService implements AiService {

    /** 系统提示词 — 定义 AI 的角色和行为 */
    private final String systemPrompt;

    /** DeepSeek API 客户端（使用已有的封装） */
    private final DeepSeekClient client;

    /**
     * 对话历史 — 保留最近的消息，让 AI 有上下文记忆。
     * 最多保留 20 条消息（即 10 轮对话）。
     */
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * 创建一个 DeepSeek AI 服务。
     *
     * @param systemPrompt 系统提示词，定义 AI 的行为。例如：
     *                     "你是一个友好的微信助手，请用简洁自然的中文回复。"
     * @throws IllegalStateException 如果环境变量 DEEPSEEK_API_KEY 未设置
     */
    public DeepSeekAiService(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        // 使用已有的配置工具从环境变量读取 API Key
        this.client = DeepSeekConfig.createClient();
        System.out.println("[AI] DeepSeek 服务已就绪");
        System.out.println("[AI] 系统提示词: " + systemPrompt);
    }

    /**
     * 与 DeepSeek AI 进行对话。
     *
     * <p>工作流程：
     * <ol>
     *   <li>将用户消息加入对话历史</li>
     *   <li>如果历史太长（超过 20 条），删除最旧的</li>
     *   <li>组装完整消息列表：系统提示词 + 历史对话 + 当前消息</li>
     *   <li>调用 DeepSeek API</li>
     *   <li>将 AI 回复加入对话历史</li>
     *   <li>返回 AI 回复</li>
     * </ol>
     *
     * @param userMessage 用户发送的消息文本
     * @return AI 生成的回复文本，调用失败时返回友好提示
     */
    @Override
    public String chat(String userMessage) {
        try {
            // 1. 将用户消息加入历史
            history.add(ChatMessage.user(userMessage));

            // 2. 控制历史长度 — 保留最近 20 条（10 轮对话）
            while (history.size() > 20) {
                history.remove(0);
            }

            // 3. 组装完整的消息列表：系统提示词 + 历史对话
            List<ChatMessage> fullMessages = new ArrayList<>();
            fullMessages.add(ChatMessage.system(systemPrompt));
            fullMessages.addAll(history);

            // 4. 调用 DeepSeek API
            String reply = client.chat(fullMessages);

            // 5. 将 AI 回复加入历史
            history.add(ChatMessage.assistant(reply));

            return reply;

        } catch (Exception e) {
            // API 调用失败 — 打印错误但不要崩溃，返回友好提示
            System.err.println("[AI] ❌ DeepSeek 调用失败: " + e.getMessage());
            return "抱歉，我暂时无法回复，请稍后再试。😅";
        }
    }

    /**
     * 清空对话历史，让 AI "忘记"之前的对话。
     * 如果需要开始全新对话，可以调用此方法。
     */
    public void clearHistory() {
        history.clear();
        System.out.println("[AI] 对话历史已清空");
    }
}
