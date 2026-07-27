package org.example.bot.impl;

import org.example.bot.db.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;

/**
 * 多会话管理器 — 每个用户可以创建多个独立对话，互不影响。
 * <p>数据通过 {@link DatabaseManager} 持久化到 SQLite。</p>
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
    private final DatabaseManager db;
    private final Gson gson = new Gson();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>(){}.getType();

    public SessionManager(DatabaseManager db, String defaultPersona, String techInstructions) {
        this.db = db;
        this.defaultPersona = defaultPersona;
        this.techInstructions = techInstructions;
        loadAllFromDb();
        System.out.println("[Session] 💾 已从数据库加载会话数据");
    }

    // ======================== 持久化 ========================

    /** 启动时从 SQLite 加载所有用户数据 */
    private void loadAllFromDb() {
        try {
            // 加载所有用户的会话 —— 直接从 sessions 表遍历
            // 简易实现：在首次 getOrCreate 时懒加载
        } catch (Exception e) {
            System.err.println("[DB] 加载会话失败: " + e.getMessage());
        }
    }

    /** 将某个用户的当前会话状态保存到 DB */
    private void saveUserToDb(String userId) {
        try {
            Map<String, Session> userSessions = sessions.get(userId);
            String curName = currentSession.get(userId);
            if (curName == null) curName = "默认";

            if (userSessions != null) {
                for (Map.Entry<String, Session> entry : userSessions.entrySet()) {
                    Session s = entry.getValue();
                    db.saveSession(userId, entry.getKey(), s.persona, s.roles, s.contents);
                }
            }

            boolean vMode = voiceMode.getOrDefault(userId, false);
            db.savePrefs(userId, curName, vMode);
        } catch (Exception e) {
            System.err.println("[DB] 保存用户 " + userId + " 数据失败: " + e.getMessage());
        }
    }

    /** 懒加载：从 DB 恢复某个用户的全部会话 */
    private void ensureLoaded(String userId) {
        if (sessions.containsKey(userId)) return; // 已加载

        try {
            // 1. 加载会话
            var loaded = db.loadSessions(userId);
            Map<String, Session> userSessions = new LinkedHashMap<>();
            for (var entry : loaded.entrySet()) {
                String name = entry.getKey();
                var row = entry.getValue();
                Session s = new Session(name, row.persona());
                // 解析 JSON 给 roles / contents
                List<String> roles = gson.fromJson(row.rolesJson(), STRING_LIST_TYPE);
                List<String> contents = gson.fromJson(row.contentsJson(), STRING_LIST_TYPE);
                if (roles != null && contents != null && roles.size() == contents.size()) {
                    s.roles.addAll(roles);
                    s.contents.addAll(contents);
                }
                userSessions.put(name, s);
            }
            sessions.put(userId, userSessions);

            // 2. 加载偏好
            var prefs = db.loadPrefs(userId);
            currentSession.put(userId, prefs.currentSession());
            voiceMode.put(userId, prefs.voiceMode());

        } catch (Exception e) {
            System.err.println("[DB] 加载用户 " + userId + " 数据失败: " + e.getMessage());
            // 降级：空内存
            sessions.put(userId, new LinkedHashMap<>());
            currentSession.putIfAbsent(userId, "默认");
            voiceMode.putIfAbsent(userId, false);
        }
    }

    /** 构建完整 system prompt */
    String fullSystemPrompt(Session s) {
        return s.persona + "\n" + techInstructions;
    }

    /** 获取当前会话（没有则创建默认会话） */
    public synchronized Session getOrCreate(String userId) {
        ensureLoaded(userId);
        String name = currentSession.get(userId);
        if (name == null) {
            name = "默认";
            currentSession.put(userId, name);
        }
        final String finalName = name;
        Map<String, Session> userSessions = sessions.get(userId);
        Session s = userSessions.computeIfAbsent(finalName, k -> new Session(finalName, defaultPersona));
        saveUserToDb(userId); // 持久化
        return s;
    }

    /** 创建新会话并切换过去 */
    public synchronized Session createSession(String userId, String name) {
        name = name.strip();
        if (name.isEmpty()) name = "未命名";

        ensureLoaded(userId);
        Map<String, Session> userSessions = sessions.get(userId);
        // 确保默认会话始终存在
        if (!userSessions.containsKey("默认")) {
            userSessions.put("默认", new Session("默认", defaultPersona));
        }
        // 同名覆盖
        Session session = new Session(name, defaultPersona);
        userSessions.put(name, session);
        currentSession.put(userId, name);
        saveUserToDb(userId);
        return session;
    }

    /** 切换到已有会话，不存在则创建 */
    public synchronized Session switchTo(String userId, String name) {
        name = name.strip();
        ensureLoaded(userId);
        currentSession.put(userId, name);
        saveUserToDb(userId);
        return getOrCreate(userId);
    }

    /** 删除会话，不允许删除最后一个 */
    public synchronized String deleteSession(String userId, String name) {
        name = name.strip();
        ensureLoaded(userId);
        getOrCreate(userId);  // 确保默认存在
        Map<String, Session> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.size() <= 1) {
            return "不能删除唯一的对话，至少保留一个。";
        }
        Session removed = userSessions.remove(name);
        if (removed == null) return "找不到对话「" + name + "」。";

        // DB 中删除
        try { db.deleteSession(userId, name); } catch (Exception e) {
            System.err.println("[DB] 删除会话失败: " + e.getMessage());
        }

        // 如果删除的是当前会话，切到第一个
        if (name.equals(currentSession.get(userId))) {
            String first = userSessions.keySet().iterator().next();
            currentSession.put(userId, first);
            saveUserToDb(userId);
            return "已删除「" + name + "」，当前对话：「" + first + "」。";
        }
        saveUserToDb(userId);
        return "已删除对话「" + name + "」。";
    }

    /** 列出所有会话 */
    public synchronized String listSessions(String userId) {
        ensureLoaded(userId);
        // 确保至少有一个默认会话
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

    /** 获取对话历史长度，用于决定是否发帮助 */
    public synchronized int historySize(String userId) {
        ensureLoaded(userId);
        Map<String, Session> userSessions = sessions.get(userId);
        if (userSessions == null) return 0;
        String name = currentSession.get(userId);
        if (name == null) return 0;
        Session s = userSessions.get(name);
        return s != null ? s.roles.size() : 0;
    }

    /** 清空当前会话（不删除） */
    public synchronized void clearCurrent(String userId) {
        ensureLoaded(userId);
        Session s = getOrCreate(userId);
        s.clear();
        saveUserToDb(userId);
    }

    /** 撤回当前会话的最后 N 轮对话（1轮=1条user+1条assistant） */
    public synchronized boolean undoLastN(String userId, int n) {
        ensureLoaded(userId);
        Session s = getOrCreate(userId);
        int toRemove = n * 2;
        if (toRemove > s.roles.size()) toRemove = s.roles.size();
        if (toRemove < 2) return false;
        for (int i = 0; i < toRemove; i++) {
            s.roles.remove(s.roles.size() - 1);
            s.contents.remove(s.contents.size() - 1);
        }
        saveUserToDb(userId);
        return true;
    }

    /** 修改当前会话的人设 */
    public synchronized void setPersona(String userId, String persona) {
        ensureLoaded(userId);
        Session s = getOrCreate(userId);
        s.persona = persona;
        saveUserToDb(userId);
    }

    /** 切换语音模式 */
    public synchronized boolean toggleVoiceMode(String userId) {
        ensureLoaded(userId);
        boolean current = voiceMode.getOrDefault(userId, false);
        voiceMode.put(userId, !current);
        saveUserToDb(userId);
        return !current;
    }

    /** 查询语音模式是否开启 */
    public synchronized boolean isVoiceMode(String userId) {
        ensureLoaded(userId);
        return voiceMode.getOrDefault(userId, false);
    }

    /** 获取所有 session 快照（用于恢复等） */
    public synchronized Map<String, Session> getAllSessions(String userId) {
        ensureLoaded(userId);
        return sessions.getOrDefault(userId, Collections.emptyMap());
    }
}
