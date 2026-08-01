package org.example.bot.rag;

import org.example.bot.service.DatabaseService;
import org.example.bot.service.DatabaseService.ChatRecord;

import java.util.*;

/**
 * 聊天历史检索器 — 搜索 SQLite chat_history 表。
 *
 * <p>用用户消息中的关键词做 SQL LIKE 检索，
 * 返回相关的历史聊天片段作为 RAG 上下文。
 */
public class ChatHistoryRetriever implements RAGRetriever {

    private final DatabaseService db;

    public ChatHistoryRetriever(DatabaseService db) {
        this.db = db;
    }

    @Override
    public String name() { return "chat-history"; }

    @Override
    public List<RAGChunk> retrieve(String userId, String query, int maxResults) {
        if (db == null || query == null || query.isBlank()) return List.of();

        // 提取关键词：拆词、去停用词
        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) return List.of();

        // 用每个关键词搜索，合并去重
        Map<String, RAGChunk> seen = new LinkedHashMap<>();
        for (String kw : keywords) {
            List<ChatRecord> records = db.searchChats(userId, kw, maxResults);
            for (ChatRecord r : records) {
                String key = r.content();
                if (!seen.containsKey(key)) {
                    seen.put(key, new RAGChunk("chat-history", r.content(), 0.5));
                }
            }
            if (seen.size() >= maxResults) break;
        }

        List<RAGChunk> results = new ArrayList<>(seen.values());
        if (results.size() > maxResults) results = results.subList(0, maxResults);
        return results;
    }

    /** 简单关键词提取：按空格/标点拆，过滤停用词和短词 */
    private List<String> extractKeywords(String text) {
        // 检测 RAG 触发词来决定是否真正需要检索
        String[] triggers = {"之前", "上次", "聊过", "记得", "说过", "提过",
                            "以前", "过去", "历史", "刚才", "前面"};
        boolean hasTrigger = false;
        for (String t : triggers) {
            if (text.contains(t)) { hasTrigger = true; break; }
        }
        if (!hasTrigger) return List.of(); // 不触发 RAG

        // 拆词
        String[] words = text.split("[\\s，。！？,.!?、：:；;（）()]+");
        Set<String> stopWords = Set.of("我", "你", "的", "了", "是", "吗", "呢", "吧",
            "啊", "哦", "嗯", "这", "那", "什么", "怎么", "为什么", "在哪", "哪个");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            String trimmed = w.strip();
            if (trimmed.length() >= 2 && !stopWords.contains(trimmed)) {
                keywords.add(trimmed);
            }
        }
        // 取前 5 个关键词
        if (keywords.size() > 5) keywords = keywords.subList(0, 5);
        return keywords;
    }
}
