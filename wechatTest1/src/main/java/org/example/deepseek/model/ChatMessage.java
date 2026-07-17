package org.example.deepseek.model;

/**
 * 聊天消息记录。
 *
 * @param role    角色：{@code system}, {@code user}, {@code assistant}
 * @param content 消息文本
 */
public record ChatMessage(String role, String content) {

    /** 创建 system 消息 */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    /** 创建 user 消息 */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /** 创建 assistant 消息 */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
