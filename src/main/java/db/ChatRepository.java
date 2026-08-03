package db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天记录持久化 — 读写 sessions 和 messages 表。
 */
public class ChatRepository {

    private final DatabaseManager db = DatabaseManager.getInstance();

    // ==================== 会话 ====================

    /** 确保会话存在（不存在则创建） */
    public void ensureSession(String userId, String sessionName, String persona) {
        String sql = "INSERT OR IGNORE INTO sessions (user_id, session_name, persona) VALUES (?,?,?)";
        execute(sql, userId, sessionName, persona);
    }

    /** 更新人设 */
    public void updatePersona(String userId, String sessionName, String persona) {
        String sql = "UPDATE sessions SET persona=?, updated_at=datetime('now','localtime') WHERE user_id=? AND session_name=?";
        execute(sql, persona, userId, sessionName);
    }

    /** 切换语音模式 */
    public void updateVoiceMode(String userId, String sessionName, boolean on) {
        String sql = "UPDATE sessions SET voice_mode=?, updated_at=datetime('now','localtime') WHERE user_id=? AND session_name=?";
        execute(sql, on ? 1 : 0, userId, sessionName);
    }

    /** 删除会话及其所有消息 */
    public void deleteSession(String userId, String sessionName) {
        execute("DELETE FROM messages WHERE user_id=? AND session_name=?", userId, sessionName);
        execute("DELETE FROM sessions WHERE user_id=? AND session_name=?", userId, sessionName);
    }

    /** 列出用户所有会话 */
    public List<Map<String, Object>> listSessions(String userId) {
        String sql = "SELECT session_name, persona, voice_mode, created_at FROM sessions WHERE user_id=? ORDER BY updated_at DESC";
        return query(sql, userId);
    }

    // ==================== 消息 ====================

    /** 保存一条消息 */
    public void saveMessage(String userId, String sessionName, String role, String content) {
        String sql = "INSERT INTO messages (user_id, session_name, role, content) VALUES (?,?,?,?)";
        execute(sql, userId, sessionName, role, content);
    }

    /** 批量恢复最近 N 条消息（按顺序从旧到新） */
    public List<String[]> loadHistory(String userId, String sessionName, int limit) {
        String sql = "SELECT role, content FROM ("
                   + "SELECT id, role, content FROM messages WHERE user_id=? AND session_name=? "
                   + "ORDER BY id DESC LIMIT ?"
                   + ") ORDER BY id ASC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, sessionName);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            List<String[]> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new String[]{rs.getString("role"), rs.getString("content")});
            }
            return result;
        } catch (Exception e) {
            System.err.println("[DB] 加载历史失败: " + e.getMessage());
            return List.of();
        }
    }

    // ==================== 内部工具 ====================

    private void execute(String sql, Object... params) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[DB] 写入失败 " + sql.substring(0, Math.min(sql.length(), 40))
                + " → " + e.getMessage());
        }
    }

    private List<Map<String, Object>> query(String sql, Object... params) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ResultSet rs = ps.executeQuery();
            var meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                result.add(row);
            }
        } catch (Exception e) {
            System.err.println("[DB] 查询失败 → " + e.getMessage());
        }
        return result;
    }
}
