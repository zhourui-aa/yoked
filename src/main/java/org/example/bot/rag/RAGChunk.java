package org.example.bot.rag;

/**
 * RAG 检索结果 — 一个从检索源召回的内容片段。
 */
public record RAGChunk(String source, String content, double score) {

    /** 缩略展示（用于日志） */
    public String preview() {
        int len = Math.min(60, content.length());
        return content.substring(0, len) + (content.length() > len ? "..." : "");
    }
}
