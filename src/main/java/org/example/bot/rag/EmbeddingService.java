package org.example.bot.rag;

/**
 * 嵌入服务 — 将文本转换为向量。
 *
 * <p>默认实现用本地字符 n-gram 哈希（零 API 依赖），
 * 可替换为 OpenAI text-embedding-3-small 等远程模型。
 */
public interface EmbeddingService {

    /** 向量维度 */
    int dimension();

    /** 将文本转为浮点向量 */
    float[] embed(String text);

    /** 计算两个向量的余弦相似度 [0, 1] */
    default double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
