package org.example.bot.rag;

import java.util.List;

/**
 * RAG 检索器接口 — 从指定数据源检索相关内容。
 */
public interface RAGRetriever {

    /** 检索器名称（用于日志） */
    String name();

    /**
     * 检索与查询相关的上下文片段。
     *
     * @param userId     用户 ID（用于范围限定）
     * @param query      用户消息原文
     * @param maxResults 最大返回条数
     * @return 按相关度排序的片段列表
     */
    List<RAGChunk> retrieve(String userId, String query, int maxResults);
}
