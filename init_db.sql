-- 日志数据库初始化脚本

-- 用户消息表
CREATE TABLE IF NOT EXISTS user_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    user_name TEXT,
    message TEXT NOT NULL,
    message_type TEXT DEFAULT 'text',
    created_at TEXT NOT NULL
);

-- 机器人回复表
CREATE TABLE IF NOT EXISTS bot_replies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    reply TEXT NOT NULL,
    tool_used TEXT,
    response_time_ms INTEGER,
    created_at TEXT NOT NULL
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_user_messages_user_id ON user_messages(user_id);
CREATE INDEX IF NOT EXISTS idx_user_messages_created_at ON user_messages(created_at);
CREATE INDEX IF NOT EXISTS idx_bot_replies_user_id ON bot_replies(user_id);
CREATE INDEX IF NOT EXISTS idx_bot_replies_created_at ON bot_replies(created_at);

-- 插入测试数据
INSERT OR IGNORE INTO user_messages (user_id, user_name, message, message_type, created_at)
VALUES ('test_user', '测试用户', '你好', 'text', '2026-07-27 10:00:00');

INSERT OR IGNORE INTO bot_replies (user_id, reply, tool_used, response_time_ms, created_at)
VALUES ('test_user', '你好！我是微信AI助手，有什么可以帮你的？', NULL, 500, '2026-07-27 10:00:01');