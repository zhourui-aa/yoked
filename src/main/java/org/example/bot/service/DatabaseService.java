package org.example.bot.service;

import java.util.List;
import java.util.Map;

/**
 * 数据库服务接口 — 持久化存储聊天记录、用户偏好、会话列表。
 */
public interface DatabaseService {

    // ========== 聊天记录 ==========

    /** 保存一条聊天记录 */
    void saveChat(String userId, String role, String content);

    /** 查询用户最近的聊天记录 */
    List<ChatRecord> loadChats(String userId, int limit);

    /** 删除用户当前会话的聊天记录 */
    void clearChats(String userId, String sessionName);

    // ========== 用户偏好（键值对）==========

    /** 保存一个偏好 */
    void saveUserPref(String userId, String key, String value);

    /** 读取一个偏好 */
    String loadUserPref(String userId, String key);

    /** 读取用户所有偏好 */
    Map<String, String> loadAllUserPrefs(String userId);

    // ========== 会话列表 ==========

    /** 保存会话元数据 */
    void saveSessionMeta(String userId, String sessionName, String persona);

    /** 删除会话元数据 */
    void deleteSessionMeta(String userId, String sessionName);

    /** 读取用户所有会话 */
    List<SessionMeta> loadSessionMetas(String userId);

    // ========== 聊天记录管理 ==========

    /** 按关键词搜索聊天记录 */
    List<ChatRecord> searchChats(String userId, String keyword, int limit);

    /** 统计当前会话的消息数 */
    int countChats(String userId);

    // ========== 记录类型 ==========

    record ChatRecord(String role, String content, long time) {}
    record SessionMeta(String name, String persona) {}
}
