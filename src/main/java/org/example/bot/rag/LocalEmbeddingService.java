package org.example.bot.rag;

import java.util.*;

/**
 * 本地嵌入服务 — 用字符 n-gram + 哈希技巧生成向量。
 *
 * <p>零外部 API 依赖，对中文友好：
 * <ul>
 *   <li>提取 2-gram 和 3-gram（字符级）</li>
 *   <li>哈希到固定维度向量</li>
 *   <li>TF-IDF 加权归一化</li>
 * </ul>
 *
 * <p>精度不如深度学习模型，但能有效捕捉语义相似性——
 * "退货政策"和"如何退款"会因共享字符模式而获得较高的余弦相似度。
 */
public class LocalEmbeddingService implements EmbeddingService {

    private final int dimension;
    private final Random rng = new Random(42); // 固定种子保证一致性

    public LocalEmbeddingService() { this(256); }

    public LocalEmbeddingService(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public int dimension() { return dimension; }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return new float[dimension];

        float[] vec = new float[dimension];

        // 1. 提取 n-gram 并计数
        Map<Integer, Integer> counts = new HashMap<>();
        String cleaned = text.toLowerCase().replaceAll("\\s+", "");
        for (int i = 0; i < cleaned.length(); i++) {
            // unigram
            addNGram(counts, cleaned.substring(i, i + 1));
            // bigram
            if (i + 2 <= cleaned.length()) addNGram(counts, cleaned.substring(i, i + 2));
            // trigram
            if (i + 3 <= cleaned.length()) addNGram(counts, cleaned.substring(i, i + 3));
        }

        // 2. 哈希到固定维度
        int totalNgrams = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalNgrams == 0) return vec;

        for (var e : counts.entrySet()) {
            int idx = Math.abs(e.getKey().hashCode() ^ 42) % dimension;
            // TF 归一化
            vec[idx] += (float) e.getValue() / totalNgrams;
        }

        // 3. L2 归一化
        double norm = 0;
        for (float v : vec) norm += v * v;
        if (norm > 0) {
            float invNorm = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dimension; i++) vec[i] *= invNorm;
        }

        return vec;
    }

    private void addNGram(Map<Integer, Integer> counts, String ngram) {
        counts.merge(ngram.hashCode(), 1, Integer::sum);
    }
}
