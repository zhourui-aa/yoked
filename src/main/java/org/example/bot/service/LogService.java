package org.example.bot.service;

import org.example.bot.impl.Session;

/**
 * 日志记录服务接口 — 记录用户消息和机器人回复到数据库。
 *
 * <p>场景：追踪用户与机器人的交互历史，便于分析和问题排查。
 */
public interface LogService {

    /**
     * 记录用户消息
     *
     * @param userId      用户 ID
     * @param userName    用户名（可选）
     * @param message     用户发送的消息内容
     * @param messageType 消息类型：text, voice, image, file
     */
    void logUserMessage(String userId, String userName, String message, String messageType);

    /**
     * 记录机器人回复
     *
     * @param userId         用户 ID
     * @param reply          机器人回复内容
     * @param toolUsed       使用的工具名称（可选，如 query_stock）
     * @param responseTimeMs 响应耗时（毫秒）
     */
    void logBotReply(String userId, String reply, String toolUsed, long responseTimeMs);

    /**
     * 获取用户最近的消息记录
     *
     * @param userId 用户 ID
     * @param limit  最多返回条数
     * @return 格式化的消息记录列表
     */
    String getUserHistory(String userId, int limit);

    /**
     * 获取今日统计数据
     *
     * @return 格式化的统计信息（消息数、回复数、平均响应时间等）
     */
    String getTodayStats();

    /**
     * 从数据库加载历史记录到会话中
     *
     * @param userId  用户 ID
     * @param session 会话对象
     */
    void loadHistoryIntoSession(String userId, Session session);

    /**
     * 删除指定记录
     *
     * @param tableName 表名：user_messages 或 bot_replies
     * @param id        记录 ID
     * @return 删除结果
     */
    String deleteRecord(String tableName, int id);

    /**
     * 修改指定记录
     *
     * @param tableName  表名：user_messages 或 bot_replies
     * @param id         记录 ID
     * @param newContent 新内容
     * @return 修改结果
     */
    String updateRecord(String tableName, int id, String newContent);

    /**
     * 清空指定用户的所有记录
     *
     * @param userId 用户 ID
     * @return 清空结果
     */
    String clearUserHistory(String userId);

    /**
     * 手动添加一条用户消息记录
     *
     * @param userId  用户 ID
     * @param message 消息内容
     * @return 添加结果
     */
    String addUserMessage(String userId, String message);

    /**
     * 按内容搜索并删除记录
     *
     * @param userId  用户 ID
     * @param keyword 关键词
     * @return 删除结果
     */
    String deleteByContent(String userId, String keyword);

    /**
     * 按编号删除记录（编号来自查看历史时显示的数字）
     *
     * @param userId 用户 ID
     * @param number 记录编号（1, 2, 3...）
     * @return 删除结果
     */
    String deleteByNumber(String userId, int number);

    /**
     * 按编号修改记录（编号来自查看历史时显示的数字）
     *
     * @param userId     用户 ID
     * @param number     记录编号（1, 2, 3...）
     * @param newContent 新内容
     * @return 修改结果
     */
    String updateByNumber(String userId, int number, String newContent);

}