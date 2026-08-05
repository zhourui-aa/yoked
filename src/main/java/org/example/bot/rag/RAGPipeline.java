package org.example.bot.rag;

import java.util.*;

/**
 * RAG 管道 — 管理多个检索器，编排检索→去重→格式化。
 *
 * <h3>使用</h3>
 * <pre>
 *   RAGPipeline rag = new RAGPipeline();
 *   rag.addRetriever(new ChatHistoryRetriever(db));
 *
 *   String context = rag.augment(userId, text, 5);
 *   if (!context.isEmpty()) {
 *       text = text + "\n\n【参考信息】\n" + context;
 *   }
 * </pre>
 *
 * <h3>触发策略</h3>
 * 只在用户消息包含回忆类关键词时才触发检索（"之前"、"上次"等），
 * 避免每条消息都检索造成的延迟和 token 浪费。
 */
public class RAGPipeline {

    private final List<RAGRetriever> retrievers = new ArrayList<>();
    private final int defaultMaxResults;

    public RAGPipeline() {
        this(5);
    }

    public RAGPipeline(int defaultMaxResults) {
        this.defaultMaxResults = defaultMaxResults;
    }

    /** 注册检索器 */
    public RAGPipeline addRetriever(RAGRetriever r) {
        retrievers.add(r);
        return this;
    }

    public int retrieverCount() { return retrievers.size(); }

    public String summary() {
        if (retrievers.isEmpty()) return "[RAG] 0 个检索器";
        StringBuilder sb = new StringBuilder("[RAG] ").append(retrievers.size()).append(" 个检索器:\n");
        for (RAGRetriever r : retrievers) {
            sb.append("  • ").append(r.name()).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * 检索并构建增强上下文。
     *
     * @param userId 用户 ID
     * @param query  用户消息原文
     * @return 格式化的参考信息文本，无结果时返回空字符串
     */
    public String augment(String userId, String query) {
        return augment(userId, query, defaultMaxResults);
    }

    /** @param maxResults 每个检索器的最大返回条数 */
    public String augment(String userId, String query, int maxResults) {
        if (query == null || query.isBlank()) return "";

        List<RAGChunk> allChunks = new ArrayList<>();
        for (RAGRetriever retriever : retrievers) {
            try {
                List<RAGChunk> chunks = retriever.retrieve(userId, query, maxResults);
                if (!chunks.isEmpty()) {
                    allChunks.addAll(chunks);
                    System.out.println("[RAG:" + retriever.name() + "] 检索到 " + chunks.size() + " 条");
                }
            } catch (Exception e) {
                System.err.println("[RAG:" + retriever.name() + "] ❌ " + e.getMessage());
            }
        }

        if (allChunks.isEmpty()) return "";

        // 去重（按 content）
        Set<String> seen = new HashSet<>();
        List<RAGChunk> unique = new ArrayList<>();
        for (RAGChunk c : allChunks) {
            if (seen.add(c.content())) unique.add(c);
        }

        // 限制总条数
        int limit = Math.min(unique.size(), maxResults * 2);
        unique = unique.subList(0, limit);

        // 格式化为参考信息
        StringBuilder sb = new StringBuilder();
        sb.append("以下是与此问题相关的历史聊天记录，请参考这些内容回答问题：\n");
        for (int i = 0; i < unique.size(); i++) {
            RAGChunk c = unique.get(i);
            sb.append(i + 1).append(". ").append(c.content()).append("\n");
        }

        System.out.println("[RAG] 增强上下文: " + unique.size() + " 条，共 "
            + sb.length() + " 字符");
        return sb.toString().strip();
    }
}
