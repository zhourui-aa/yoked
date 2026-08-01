package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * SQLite 数据库管理器 — 单例，负责建库建表。
 *
 * <p>数据库文件：src/main/java/db/chat.db，不存在则自动创建。
 * <p>启用 WAL 模式 + 5s 忙等超时，避免多线程并发写入时的 SQLITE_BUSY。
 */
public class DatabaseManager {

    private static final String DB_URL = "jdbc:sqlite:src/main/java/db/chat.db";
    private static volatile DatabaseManager instance;

    private DatabaseManager() {
        try {
            Class.forName("org.sqlite.JDBC");
            initTables();
            System.out.println("[DB] SQLite 已就绪（chat.db, WAL 模式）");
        } catch (Exception e) {
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) instance = new DatabaseManager();
            }
        }
        return instance;
    }

    /** 获取数据库连接（已配置 WAL + 忙等超时） */
    public Connection getConnection() throws Exception {
        Connection conn = DriverManager.getConnection(DB_URL);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    private void initTables() throws Exception {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id       TEXT NOT NULL,
                    session_name  TEXT NOT NULL DEFAULT '默认',
                    persona       TEXT,
                    voice_mode    INTEGER DEFAULT 0,
                    created_at    TEXT DEFAULT (datetime('now','localtime')),
                    updated_at    TEXT DEFAULT (datetime('now','localtime')),
                    UNIQUE(user_id, session_name)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id       TEXT NOT NULL,
                    session_name  TEXT NOT NULL,
                    role          TEXT NOT NULL CHECK(role IN ('user','assistant','system','tool')),
                    content       TEXT NOT NULL,
                    created_at    TEXT DEFAULT (datetime('now','localtime'))
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_msg_session
                ON messages(user_id, session_name, created_at)
                """);
        }
    }
}
