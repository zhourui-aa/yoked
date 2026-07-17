package org.example.deepseek.config;

import org.example.deepseek.DeepSeekClient;

/**
 * DeepSeek 配置工具。
 *
 * <p>从环境变量 {@code DEEPSEEK_API_KEY} 读取 API Key，与官方示例一致。
 * 使用方式：
 * <pre>{@code
 * DeepSeekClient ds = DeepSeekConfig.createClient();
 * }</pre>
 */
public final class DeepSeekConfig {

    private DeepSeekConfig() {}

    /**
     * 从环境变量 DEEPSEEK_API_KEY 创建客户端。
     * @throws IllegalStateException 如果环境变量未设置
     */
    public static DeepSeekClient createClient() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "请设置环境变量 DEEPSEEK_API_KEY，例如: set DEEPSEEK_API_KEY=sk-xxx");
        }
        return new DeepSeekClient(key.trim());
    }
}
