package org.example.bot.impl;

import org.example.bot.service.DatabaseService;

import java.util.*;

/**
 * 多会话管理器 — 每个用户可以创建多个独立对话，互不影响。
 * 支持 SQLite 持久化：会话列表、用户偏好、语音模式等重启后自动恢复。
 */
public class SessionManager {

    public static final int MAX_HISTORY = 200; // 100 轮对话

    // 帮助指南
    static final String HELP_MESSAGE = """
        🤖 我是你的微信AI助手，支持以下功能：

        💬 自然对话
        🎨 图片生成 — "帮我画一只猫"
        👁 图片识别 — 发送图片即可，支持连续追问
        📄 文件总结 — 发送 TXT/PDF/Word/Excel 自动总结
        🌐 联网搜索 — "搜索最近有什么大事"
        📖 网页阅读 — 发送链接自动抓取并总结
        🌤 天气查询 — "北京今天天气怎么样"
        🕐 日期时间 — "东京现在几点" / "纽约时间"
        📰 新闻 — "最新科技新闻" / "国际新闻"
        ⚽ 足球数据 — "英超积分榜" / "最近比赛" / "转会消息"
        💹 金融行情 — "茅台股价" / "BTC行情" / "基金001632"
        🥗 饮食推荐 — "减脂怎么吃" / "增肌餐推荐"
        🧮 金融计算 — "复利计算" / "房贷月供" / "个税" / "汇率"
        📦 快递查询 — "查快递 YT1234567890"
        🎵 音乐搜索 — "搜歌 晴天 周杰伦"
        🎯 成语接龙 — 说"成语接龙"开始游戏
        🗑 垃圾分类 — "电池是什么垃圾"
        🎲 随机工具 — "掷骰子" / "今晚吃什么" / "抛硬币"
        🎤 语音回复 — "发语音告诉我"（一次性）
        🔊 语音模式 — "开启语音模式" 后所有回复带语音
        🎭 设定人设 — "设定人设：你是一只猫娘"
        🎵 切换音色 — "切换音色 Ethan" 切换 TTS 音色（14种）
        🤖 多 Bot — "新建bot 客服2号" 运行时新增微信号

        📂 会话管理：
        • 新建对话「名称」
        • 切换到「名称」对话
        • 查看所有对话
        • 删掉「名称」对话""";

    // userId → Map<sessionName, Session>
    private final Map<String, Map<String, Session>> sessions = new HashMap<>();
    // userId → current session name
    private final Map<String, String> currentSession = new HashMap<>();
    // userId → voice mode
    private final Map<String, Boolean> voiceMode = new HashMap<>();
    // 全局默认人设和技术指令
    private final String defaultPersona;
    private final String techInstructions;
    // 数据库持久化
    private final DatabaseService db;
    // 已从 DB 恢复的用户
    private final Set<String> restored = new HashSet<>();

    public SessionManager(String defaultPersona, String techInstructions, DatabaseService db) {
        this.defaultPersona = defaultPersona;
        this.techInstructions = techInstructions;
        this.db = db;
    }

    /** 构建完整 system prompt */
    String fullSystemPrompt(Session s) {
        return s.persona + "\n" + techInstructions;
    }

    /** 从 DB 恢复用户的所有状态 */
    private synchronized void restoreIfNeeded(String userId) {
        if (!restored.add(userId)) return;
        if (db == null) return;

        // 1. 恢复会话列表
        var metas = db.loadSessionMetas(userId);
        Map<String, Session> userSessions = new LinkedHashMap<>();
        for (var m : metas) {
            Session s = new Session(m.name(), m.persona().isEmpty() ? defaultPersona : m.persona());
            userSessions.put(m.name(), s);
        }
        if (!userSessions.isEmpty()) {
            sessions.put(userId, userSessions);
        }

        // 2. 恢复当前会话
        String cur = db.loadUserPref(userId, "current_session");
        if (cur != null && !cur.isBlank()) {
            currentSession.put(userId, cur);
        } else if (userSessions.isEmpty()) {
            currentSession.put(userId, "默认");
        }

        // 3. 恢复语音模式
        String vm = db.loadUserPref(userId, "voice_mode");
        if ("true".equals(vm)) voiceMode.put(userId, true);
    }

    /** 获取当前会话（没有则创建默认会话） */
    public synchronized Session getOrCreate(String userId) {
        restoreIfNeeded(userId);
        String name = currentSession.get(userId);
        if (name == null) {
            name = "默认";
            currentSession.put(userId, name);
        }
        final String sessionName = name;
        Map<String, Session> userSessions = sessions.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        Session s = userSessions.computeIfAbsent(sessionName, k -> new Session(sessionName, defaultPersona));
        setCurrentSessionTag(sessionName);
        return s;
    }

    /** 创建新会话并切换过去 */
    public synchronized Session createSession(String userId, String name) {
        name = name.strip();
        if (name.isEmpty()) name = "未命名";
        restoreIfNeeded(userId);

        Map<String, Session> userSessions = sessions.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        if (!userSessions.containsKey("默认")) {
            userSessions.put("默认", new Session("默认", defaultPersona));
        }
        Session session = new Session(name, defaultPersona);
        userSessions.put(name, session);
        currentSession.put(userId, name);
        setCurrentSessionTag(name);

        if (db != null) db.saveSessionMeta(userId, name, defaultPersona);
        return session;
    }

    /** 切换到已有会话，不存在则创建 */
    public synchronized Session switchTo(String userId, String name) {
        name = name.strip();
        restoreIfNeeded(userId);
        currentSession.put(userId, name);
        setCurrentSessionTag(name);
        if (db != null) db.saveUserPref(userId, "current_session", name);
        return getOrCreate(userId);
    }

    /** 删除会话，不允许删除最后一个 */
    public synchronized String deleteSession(String userId, String name) {
        name = name.strip();
        restoreIfNeeded(userId);
        getOrCreate(userId);
        Map<String, Session> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.size() <= 1) {
            return "不能删除唯一的对话，至少保留一个。";
        }
        Session removed = userSessions.remove(name);
        if (removed == null) return "找不到对话「" + name + "」。";

        if (db != null) {
            db.deleteSessionMeta(userId, name);
            db.clearChats(userId, name);
        }

        if (name.equals(currentSession.get(userId))) {
            String first = userSessions.keySet().iterator().next();
            currentSession.put(userId, first);
            setCurrentSessionTag(first);
            if (db != null) db.saveUserPref(userId, "current_session", first);
            return "已删除「" + name + "」，当前对话：「" + first + "」。";
        }
        return "已删除对话「" + name + "」。";
    }

    /** 列出所有会话 */
    public synchronized String listSessions(String userId) {
        getOrCreate(userId);
        Map<String, Session> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.isEmpty()) return "你还没有任何对话。";

        String current = currentSession.get(userId);
        StringBuilder sb = new StringBuilder("📂 你的对话列表：\n");
        for (String name : userSessions.keySet()) {
            sb.append(name.equals(current) ? "  ● " : "  ○ ");
            sb.append(name);
            Session s = userSessions.get(name);
            sb.append("（").append(s.roles.size() / 2).append("轮）");
            if (name.equals(current)) sb.append(" ← 当前");
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    public synchronized int historySize(String userId) {
        Map<String, Session> userSessions = sessions.get(userId);
        if (userSessions == null) return 0;
        String name = currentSession.get(userId);
        if (name == null) return 0;
        Session s = userSessions.get(name);
        return s != null ? s.roles.size() : 0;
    }

    public synchronized void clearCurrent(String userId) {
        Session s = getOrCreate(userId);
        s.clear();
        String sessionName = currentSession.get(userId);
        if (db != null && sessionName != null) db.clearChats(userId, sessionName);
    }

    public synchronized void setPersona(String userId, String persona) {
        Session s = getOrCreate(userId);
        s.persona = persona;
        // 持久化到 session 的 persona 字段
        String name = currentSession.get(userId);
        if (db != null && name != null) db.saveSessionMeta(userId, name, persona);
        // 同时保存为默认人设偏好
        if (db != null) db.saveUserPref(userId, "persona", persona);
    }

    public synchronized boolean toggleVoiceMode(String userId) {
        restoreIfNeeded(userId);
        boolean current = voiceMode.getOrDefault(userId, false);
        voiceMode.put(userId, !current);
        if (db != null) db.saveUserPref(userId, "voice_mode", String.valueOf(!current));
        return !current;
    }

    public synchronized boolean isVoiceMode(String userId) {
        restoreIfNeeded(userId);
        return voiceMode.getOrDefault(userId, false);
    }

    public synchronized Map<String, Session> getAllSessions(String userId) {
        return sessions.getOrDefault(userId, Collections.emptyMap());
    }

    /** 设置 ThreadLocal 当前会话名，供 SqliteDatabaseServiceImpl 使用 */
    private void setCurrentSessionTag(String name) {
        if (name != null) SqliteDatabaseServiceImpl.CURRENT_SESSION.set(name);
    }
}
