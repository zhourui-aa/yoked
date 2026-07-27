package org.example.bot.impl;

import org.example.bot.service.LogService;
import org.example.bot.util.ConfigUtil;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 日志记录服务实现 — 基于 SQLite 数据库。
 *
 * <p>无需安装数据库服务，数据库文件自动创建，表结构自动初始化。
 * 支持记录用户消息、机器人回复、查询历史记录和统计数据。
 */
public class LogServiceImpl implements LogService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String dbPath;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public LogServiceImpl() {
        String path = ConfigUtil.get("db.path", "DB_PATH");
        if (path == null || path.isBlank()) {
            path = "data/bot_log.db";
        }
        // 将相对路径转换为基于项目根目录的绝对路径
        this.dbPath = toAbsolutePath(path);
        System.out.println("[日志] 📋 数据库路径: " + this.dbPath);
        init();
    }

    private String toAbsolutePath(String path) {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (p.isAbsolute()) {
            return path;
        }
        // 基于当前类所在目录的父目录（项目根目录）
        java.nio.file.Path classPath = java.nio.file.Paths.get(System.getProperty("user.dir"));
        return classPath.resolve(path).normalize().toString();
    }

    // ==================== 初始化 ====================

    private void init() {
        if (initialized.compareAndSet(false, true)) {
            try (Connection conn = getConnection()) {
                createTables(conn);
                System.out.println("[日志] ✅ 日志服务已就绪");
            } catch (Exception e) {
                System.err.println("[日志] ❌ 初始化失败: " + e.getMessage());
                initialized.set(false);
            }
        }
    }

    private Connection getConnection() throws SQLException {
        // 创建 data 目录（如果不存在）
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(dbPath);
            java.nio.file.Path parent = path.getParent();
            if (parent != null && !java.nio.file.Files.exists(parent)) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (Exception ignored) {}

        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 用户消息表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    user_name TEXT,
                    message TEXT NOT NULL,
                    message_type TEXT DEFAULT 'text',
                    created_at TEXT NOT NULL
                )""");

            // 机器人回复表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bot_replies (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    reply TEXT NOT NULL,
                    tool_used TEXT,
                    response_time_ms INTEGER,
                    created_at TEXT NOT NULL
                )""");

            // 创建索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_user_messages_user_id ON user_messages(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_user_messages_created_at ON user_messages(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bot_replies_user_id ON bot_replies(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bot_replies_created_at ON bot_replies(created_at)");
        }
    }

    // ==================== 对外接口 ====================

    @Override
    public void logUserMessage(String userId, String userName, String message, String messageType) {
        if (!initialized.get()) return;
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO user_messages (user_id, user_name, message, message_type, created_at) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.setString(2, userName);
                pstmt.setString(3, truncate(message, 5000));
                pstmt.setString(4, messageType);
                pstmt.setString(5, LocalDateTime.now().format(FORMATTER));
                pstmt.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[日志] ❌ 记录用户消息失败: " + e.getMessage());
        }
    }

    @Override
    public void logBotReply(String userId, String reply, String toolUsed, long responseTimeMs) {
        if (!initialized.get()) return;
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO bot_replies (user_id, reply, tool_used, response_time_ms, created_at) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.setString(2, truncate(reply, 5000));
                pstmt.setString(3, toolUsed);
                pstmt.setLong(4, responseTimeMs);
                pstmt.setString(5, LocalDateTime.now().format(FORMATTER));
                pstmt.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[日志] ❌ 记录机器人回复失败: " + e.getMessage());
        }
    }

    @Override
    public String getUserHistory(String userId, int limit) {
        if (!initialized.get()) return "日志服务未就绪";
        if (limit <= 0) limit = 20;

        StringBuilder sb = new StringBuilder();
        sb.append("📋 当前用户ID: ").append(userId).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        try (Connection conn = getConnection()) {
            // 先查当前用户的记录
            String sql = """
                SELECT '用户' as type, message as content, created_at 
                FROM user_messages 
                WHERE user_id = ? 
                UNION ALL 
                SELECT '机器人' as type, reply as content, created_at 
                FROM bot_replies 
                WHERE user_id = ? 
                ORDER BY created_at DESC 
                LIMIT ?""";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.setString(2, userId);
                pstmt.setInt(3, limit);

                try (ResultSet rs = pstmt.executeQuery()) {
                    boolean hasData = false;
                    while (rs.next()) {
                        hasData = true;
                        String type = rs.getString("type");
                        String content = rs.getString("content");
                        String time = rs.getString("created_at");

                        sb.append("\n[").append(time).append("] ");
                        sb.append(type.equals("用户") ? "👤" : "🤖").append(" ");
                        sb.append(truncate(content, 100));
                    }

                    if (!hasData) {
                        // 当前用户没有记录，查询所有用户的记录
                        sb.append("当前用户暂无记录，以下是所有用户的历史记录：\n");
                        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

                        String allSql = """
                            SELECT '用户' as type, user_id, message as content, created_at 
                            FROM user_messages 
                            UNION ALL 
                            SELECT '机器人' as type, user_id, reply as content, created_at 
                            FROM bot_replies 
                            ORDER BY created_at DESC 
                            LIMIT ?""";

                        try (PreparedStatement allStmt = conn.prepareStatement(allSql)) {
                            allStmt.setInt(1, limit);
                            try (ResultSet allRs = allStmt.executeQuery()) {
                                boolean hasAllData = false;
                                while (allRs.next()) {
                                    hasAllData = true;
                                    String uid = allRs.getString("user_id");
                                    String type = allRs.getString("type");
                                    String content = allRs.getString("content");
                                    String time = allRs.getString("created_at");

                                    sb.append("\n[").append(time).append("] ");
                                    sb.append(type.equals("用户") ? "👤" : "🤖").append(" ");
                                    sb.append("(").append(truncate(uid, 20)).append(") ");
                                    sb.append(truncate(content, 100));
                                }
                                if (!hasAllData) {
                                    sb.append("数据库中暂无任何记录\n");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            return "❌ 查询失败: " + e.getMessage();
        }

        return sb.toString();
    }

    @Override
    public String getTodayStats() {
        if (!initialized.get()) return "日志服务未就绪";

        String today = LocalDate.now().toString();
        StringBuilder sb = new StringBuilder();
        sb.append("📊 今日统计（").append(today).append("）\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        try (Connection conn = getConnection()) {
            // 用户消息数
            int msgCount = countToday(conn, "user_messages", today);
            sb.append("👤 用户消息：").append(msgCount).append(" 条\n");

            // 机器人回复数
            int replyCount = countToday(conn, "bot_replies", today);
            sb.append("🤖 机器人回复：").append(replyCount).append(" 条\n");

            // 平均响应时间
            String avgTimeSql = """
                SELECT AVG(response_time_ms) FROM bot_replies 
                WHERE created_at LIKE ?""";
            try (PreparedStatement pstmt = conn.prepareStatement(avgTimeSql)) {
                pstmt.setString(1, today + "%");
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next() && rs.getDouble(1) > 0) {
                        sb.append("⏱ 平均响应：").append((int) rs.getDouble(1)).append(" ms\n");
                    }
                }
            }

            // 使用最多的工具
            String topToolSql = """
                SELECT tool_used, COUNT(*) as cnt FROM bot_replies 
                WHERE created_at LIKE ? AND tool_used IS NOT NULL AND tool_used != '' 
                GROUP BY tool_used ORDER BY cnt DESC LIMIT 3""";
            try (PreparedStatement pstmt = conn.prepareStatement(topToolSql)) {
                pstmt.setString(1, today + "%");
                try (ResultSet rs = pstmt.executeQuery()) {
                    boolean hasTools = false;
                    while (rs.next()) {
                        if (!hasTools) {
                            sb.append("🔧 常用工具：\n");
                            hasTools = true;
                        }
                        sb.append("  - ").append(rs.getString("tool_used"))
                          .append("：").append(rs.getInt("cnt")).append(" 次\n");
                    }
                    if (!hasTools) {
                        sb.append("🔧 常用工具：无\n");
                    }
                }
            }

            // 活跃用户数
            int activeUsers = countDistinctToday(conn, "user_messages", today);
            sb.append("👥 活跃用户：").append(activeUsers).append(" 人\n");

        } catch (Exception e) {
            return "❌ 查询失败: " + e.getMessage();
        }

        return sb.toString();
    }

    // ==================== 内部方法 ====================

    private int countToday(Connection conn, String table, String today) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE created_at LIKE ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, today + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int countDistinctToday(Connection conn, String table, String today) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT user_id) FROM " + table + " WHERE created_at LIKE ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, today + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 3) + "...";
    }

    @Override
    public void loadHistoryIntoSession(String userId, Session session) {
        if (!initialized.get()) return;

        try (Connection conn = getConnection()) {
            // 查询用户和机器人的消息，按时间顺序排列
            String sql = """
                SELECT 'user' as type, message as content, created_at 
                FROM user_messages 
                WHERE user_id = ? 
                UNION ALL 
                SELECT 'assistant' as type, reply as content, created_at 
                FROM bot_replies 
                WHERE user_id = ? 
                ORDER BY created_at ASC 
                LIMIT 100""";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.setString(2, userId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        String type = rs.getString("type");
                        String content = rs.getString("content");
                        session.add(type, content);
                        count++;
                    }
                    if (count > 0) {
                        System.out.println("[日志] 已加载 " + count + " 条历史记录到会话（用户: " + userId + "）");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[日志] ❌ 加载历史记录失败: " + e.getMessage());
        }
    }
}