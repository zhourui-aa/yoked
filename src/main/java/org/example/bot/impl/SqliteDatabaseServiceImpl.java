package org.example.bot.impl;

import org.example.bot.service.DatabaseService;
import org.example.bot.util.ConfigUtil;

import java.sql.*;
import java.util.*;

/**
 * SQLite 数据库服务实现 — 本地文件存储，零配置。
 *
 * <p>数据库路径可通过 sqlite.db.path 配置（默认 yoked.db）。
 */
public class SqliteDatabaseServiceImpl implements DatabaseService {

    private final String dbPath;

    public SqliteDatabaseServiceImpl() {
        String path = ConfigUtil.get("sqlite.db.path", "SQLITE_DB_PATH");
        this.dbPath = "jdbc:sqlite:" + (path != null ? path.strip() : "yoked.db");
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS chat_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    session_name TEXT DEFAULT '默认',
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    time INTEGER NOT NULL
                )
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS user_prefs (
                    user_id TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    PRIMARY KEY (user_id, key)
                )
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    user_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    persona TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (user_id, name)
                )
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_chat_user ON chat_history(user_id, session_name, time)");
            System.out.println("[数据库] SQLite 已就绪（yoked.db，3 张表）");
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 初始化失败: " + e.getMessage());
        }
    }

    // ==================== 聊天记录 ====================

    @Override
    public void saveChat(String userId, String role, String content) {
        String sql = "INSERT INTO chat_history (user_id, session_name, role, content, time) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, CURRENT_SESSION.get() != null ? CURRENT_SESSION.get() : "默认");
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setLong(5, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 保存聊天失败: " + e.getMessage());
        }
    }

    @Override
    public List<ChatRecord> loadChats(String userId, int limit) {
        List<ChatRecord> records = new ArrayList<>();
        String sessionName = CURRENT_SESSION.get() != null ? CURRENT_SESSION.get() : "默认";
        String sql = "SELECT role, content, time FROM chat_history WHERE user_id = ? AND session_name = ? ORDER BY time ASC LIMIT ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, sessionName);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new ChatRecord(
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getLong("time")
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 查询聊天失败: " + e.getMessage());
        }
        return records;
    }

    @Override
    public void clearChats(String userId, String sessionName) {
        String sql = "DELETE FROM chat_history WHERE user_id = ? AND session_name = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, sessionName);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 清除聊天失败: " + e.getMessage());
        }
    }

    // ==================== 用户偏好 ====================

    @Override
    public void saveUserPref(String userId, String key, String value) {
        String sql = "INSERT OR REPLACE INTO user_prefs (user_id, key, value) VALUES (?, ?, ?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 保存偏好失败: " + e.getMessage());
        }
    }

    @Override
    public String loadUserPref(String userId, String key) {
        String sql = "SELECT value FROM user_prefs WHERE user_id = ? AND key = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 读取偏好失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public Map<String, String> loadAllUserPrefs(String userId) {
        Map<String, String> prefs = new LinkedHashMap<>();
        String sql = "SELECT key, value FROM user_prefs WHERE user_id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) prefs.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 读取偏好失败: " + e.getMessage());
        }
        return prefs;
    }

    // ==================== 会话列表 ====================

    @Override
    public void saveSessionMeta(String userId, String sessionName, String persona) {
        String sql = "INSERT OR REPLACE INTO sessions (user_id, name, persona) VALUES (?, ?, ?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, sessionName);
            ps.setString(3, persona);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 保存会话失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteSessionMeta(String userId, String sessionName) {
        String sql = "DELETE FROM sessions WHERE user_id = ? AND name = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, sessionName);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 删除会话失败: " + e.getMessage());
        }
    }

    @Override
    public List<SessionMeta> loadSessionMetas(String userId) {
        List<SessionMeta> metas = new ArrayList<>();
        String sql = "SELECT name, persona FROM sessions WHERE user_id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    metas.add(new SessionMeta(
                        rs.getString("name"),
                        rs.getString("persona")
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 读取会话列表失败: " + e.getMessage());
        }
        return metas;
    }

    // ==================== 聊天记录管理 ====================

    @Override
    public List<ChatRecord> searchChats(String userId, String keyword, int limit) {
        List<ChatRecord> records = new ArrayList<>();
        String sessionName = CURRENT_SESSION.get() != null ? CURRENT_SESSION.get() : "默认";
        String sql = "SELECT role, content, time FROM chat_history WHERE user_id = ? AND session_name = ? AND content LIKE ? ORDER BY time ASC LIMIT ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, sessionName);
            ps.setString(3, "%" + keyword + "%");
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new ChatRecord(
                        rs.getString("role"), rs.getString("content"), rs.getLong("time")));
                }
            }
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 搜索聊天失败: " + e.getMessage());
        }
        return records;
    }

    @Override
    public int countChats(String userId) {
        String sessionName = CURRENT_SESSION.get() != null ? CURRENT_SESSION.get() : "默认";
        String sql = "SELECT COUNT(*) FROM chat_history WHERE user_id = ? AND session_name = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, sessionName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            System.err.println("[数据库] ❌ 统计聊天失败: " + e.getMessage());
        }
        return 0;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbPath);
    }

    // ThreadLocal：saveChat/loadChats 需要知道当前会话名
    public static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();
}
