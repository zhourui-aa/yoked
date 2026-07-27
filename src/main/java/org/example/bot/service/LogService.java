package org.example.bot.service;

/**
 * 日志记录服务接口 — 记录用户消息和机器人回复到数据库。
 *
 * <p>场景：追踪用户与机器人的交互历史，便于分析和问题排查。
 */
public interface LogService {

    /**
     * 记录用户消息
     * @param userId 用户 ID
     * @param userName 用户名（可选）
     * @param message 用户发送的消息内容
     * @param messageType 消息类型：text, voice, image, file
     */
    void logUserMessage(String userId, String userName, String message, String messageType);

    /**
     * 记录机器人回复
     * @param userId 用户 ID
     * @param reply 机器人回复内容
     * @param toolUsed 使用的工具名称（可选，如 query_stock）
     * @param responseTimeMs 响应耗时（毫秒）
     */
    void logBotReply(String userId, String reply, String toolUsed, long responseTimeMs);

    /**
     * 获取用户最近的消息记录
     * @param userId 用户 ID
     * @param limit 最多返回条数
     * @return 格式化的消息记录列表
     */
    String getUserHistory(String userId, int limit);

    /**
     * 获取今日统计数据
     * @return 格式化的统计信息（消息数、回复数、平均响应时间等）
     */
    String getTodayStats();
}