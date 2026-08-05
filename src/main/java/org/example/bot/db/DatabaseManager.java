package org.example.bot.db;

import java.sql.*;
import java.util.*;

/**
 * SQLite 数据库管理器 — 负责连接、建表、读写对话数据。
 * <p>
 * 生成的 chat.db 文件可以用 SQLiteStudio 直接打开查看。
 */
public class DatabaseManager implements AutoCloseable {

    private final Connection conn;

    /**
     * 初始化数据库连接，自动建表。
     *
     * @param dbPath 数据库文件路径，例如 "chat.db" 表示项目目录下的 chat.db
     */
    public DatabaseManager(String dbPath) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            // 开启 WAL 模式（提升并发性能），必须关闭 ResultSet 避免锁表
            var pragmaStmt = conn.createStatement();
            var rs = pragmaStmt.executeQuery("PRAGMA journal_mode=WAL");
            if (rs.next()) rs.getString(1);
            rs.close();
            pragmaStmt.close();
            createTables();
            System.out.println("[DB] 📁 数据库已连接: " + dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("无法连接数据库: " + dbPath, e);
        }
    }

    // ======================== 建表 ========================

    private void createTables() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 对话表 — 每个用户可以有多个会话
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    user_id     TEXT NOT NULL,
                    name        TEXT NOT NULL DEFAULT '默认',
                    persona     TEXT NOT NULL DEFAULT '',
                    roles       TEXT NOT NULL DEFAULT '[]',
                    contents    TEXT NOT NULL DEFAULT '[]',
                    created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                    updated_at  TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                    PRIMARY KEY (user_id, name)
                )
            """);

            // 用户偏好表 — 语音模式、当前会话等
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_prefs (
                    user_id         TEXT PRIMARY KEY,
                    current_session TEXT NOT NULL DEFAULT '默认',
                    voice_mode      INTEGER NOT NULL DEFAULT 0,
                    created_at      TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                    updated_at      TEXT NOT NULL DEFAULT (datetime('now','localtime'))
                )
            """);
        }
    }

    // ======================== 对话读写 ========================

    /** 加载指定用户的所有会话 */
    public Map<String, SessionRow> loadSessions(String userId) throws SQLException {
        Map<String, SessionRow> result = new LinkedHashMap<>();
        String sql = "SELECT name, persona, roles, contents FROM sessions WHERE user_id = ? ORDER BY updated_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                SessionRow row = new SessionRow(
                    rs.getString("name"),
                    rs.getString("persona"),
                    rs.getString("roles"),
                    rs.getString("contents")
                );
                result.put(row.name, row);
            }
        }
        return result;
    }

    /** 保存（插入或替换）一个会话 */
    public void saveSession(String userId, String name, String persona,
                            List<String> roles, List<String> contents) throws SQLException {
        String sql = """
            INSERT INTO sessions (user_id, name, persona, roles, contents, updated_at)
            VALUES (?, ?, ?, ?, ?, datetime('now','localtime'))
            ON CONFLICT(user_id, name) DO UPDATE SET
                persona   = excluded.persona,
                roles     = excluded.roles,
                contents  = excluded.contents,
                updated_at = excluded.updated_at
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, name);
            ps.setString(3, persona);
            ps.setString(4, toJsonArray(roles));
            ps.setString(5, toJsonArray(contents));
            ps.executeUpdate();
        }
    }

    /** 删除一个会话 */
    public void deleteSession(String userId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM sessions WHERE user_id = ? AND name = ?")) {
            ps.setString(1, userId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }


    // ======================== 搜索聊天记录 ========================

    /** 在用户的所有会话内容中搜索关键词，返回匹配的消息列表 */
    public List<SearchResult> searchContent(String userId, String keyword, int limit) throws SQLException {
        List<SearchResult> results = new ArrayList<>();
        String sql = "SELECT name, roles, contents FROM sessions WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String sessionName = rs.getString("name");
                String rolesJson = rs.getString("roles");
                String contentsJson = rs.getString("contents");
                // 用 Gson 解析 JSON
                com.google.gson.Gson gson = new com.google.gson.Gson();
                java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<List<String>>(){}.getType();
                List<String> roles = gson.fromJson(rolesJson, listType);
                List<String> contents = gson.fromJson(contentsJson, listType);
                if (roles == null || contents == null) continue;
                for (int i = 0; i < contents.size(); i++) {
                    if (contents.get(i).contains(keyword)) {
                        results.add(new SearchResult(sessionName, roles.get(i), contents.get(i), i));
                        if (results.size() >= limit) return results;
                    }
                }
            }
        }
        return results;
    }

    /** 搜索结果的记录 */
    public record SearchResult(String sessionName, String role, String content, int index) {}
    // ======================== 用户偏好读写 ========================

    /** 加载用户偏好 */
    public UserPrefs loadPrefs(String userId) throws SQLException {
        String sql = "SELECT current_session, voice_mode FROM user_prefs WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new UserPrefs(
                    rs.getString("current_session"),
                    rs.getInt("voice_mode") == 1
                );
            }
        }
        return new UserPrefs("默认", false);
    }

    /** 保存用户偏好 */
    public void savePrefs(String userId, String currentSession, boolean voiceMode) throws SQLException {
        String sql = """
            INSERT INTO user_prefs (user_id, current_session, voice_mode, updated_at)
            VALUES (?, ?, ?, datetime('now','localtime'))
            ON CONFLICT(user_id) DO UPDATE SET
                current_session = excluded.current_session,
                voice_mode      = excluded.voice_mode,
                updated_at      = excluded.updated_at
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, currentSession);
            ps.setInt(3, voiceMode ? 1 : 0);
            ps.executeUpdate();
        }
    }

    // ======================== 工具方法 ========================

    private static String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(escapeJson(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /** 关闭连接 */
    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
                System.out.println("[DB] 数据库连接已关闭");
            }
        } catch (SQLException e) {
            System.err.println("[DB] 关闭数据库时出错: " + e.getMessage());
        }
    }

    // ======================== 数据类 ========================

    /** 一行会话数据 */
    public record SessionRow(String name, String persona, String rolesJson, String contentsJson) {}

    /** 用户偏好 */
    public record UserPrefs(String currentSession, boolean voiceMode) {}
}
