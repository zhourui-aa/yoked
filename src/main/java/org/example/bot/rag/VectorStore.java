package org.example.bot.rag;

import java.sql.*;
import java.util.*;

/**
 * 向量存储 — SQLite 存文本+向量，Java 算余弦相似度。
 *
 * <p>表结构：
 * <pre>
 * CREATE TABLE rag_chunks (
 *     id INTEGER PRIMARY KEY AUTOINCREMENT,
 *     source TEXT NOT NULL,          -- 来源（文件名或 "chat-history"）
 *     content TEXT NOT NULL,         -- 原文片段
 *     embedding TEXT NOT NULL,       -- 向量 JSON 数组 "[0.1, 0.2, ...]"
 *     created_at INTEGER NOT NULL
 * )
 * </pre>
 */
public class VectorStore {

    private final String dbPath;
    private final EmbeddingService embedder;

    public VectorStore(String dbPath, EmbeddingService embedder) {
        this.dbPath = "jdbc:sqlite:" + dbPath;
        this.embedder = embedder;
        initTable();
    }

    private void initTable() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS rag_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source TEXT NOT NULL,
                    content TEXT NOT NULL,
                    embedding TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_rag_source ON rag_chunks(source)");
        } catch (Exception e) {
            System.err.println("[VectorStore] ❌ 初始化失败: " + e.getMessage());
        }
    }

    // ==================== 写入 ====================

    /** 索引一条文本（自动嵌入） */
    public void insert(String source, String content) {
        float[] vec = embedder.embed(content);
        String json = vectorToJson(vec);
        String sql = "INSERT INTO rag_chunks (source, content, embedding, created_at) VALUES (?, ?, ?, ?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, content);
            ps.setString(3, json);
            ps.setLong(4, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[VectorStore] ❌ 插入失败: " + e.getMessage());
        }
    }

    /** 批量索引（每个分片单独一行） */
    public void insertAll(String source, List<String> chunks) {
        String sql = "INSERT INTO rag_chunks (source, content, embedding, created_at) VALUES (?, ?, ?, ?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            long now = System.currentTimeMillis() / 1000;
            for (String chunk : chunks) {
                float[] vec = embedder.embed(chunk);
                ps.setString(1, source);
                ps.setString(2, chunk);
                ps.setString(3, vectorToJson(vec));
                ps.setLong(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            System.out.println("[VectorStore] 索引完成: " + source + " → " + chunks.size() + " 条");
        } catch (Exception e) {
            System.err.println("[VectorStore] ❌ 批量插入失败: " + e.getMessage());
        }
    }

    // ==================== 检索 ====================

    /**
     * 向量相似度检索。
     * @param query  查询文本
     * @param limit  最大返回条数
     * @param minScore 最低相似度阈值 [0, 1]
     */
    public List<RAGChunk> search(String query, int limit, double minScore) {
        float[] queryVec = embedder.embed(query);
        List<RAGChunk> results = new ArrayList<>();

        String sql = "SELECT id, source, content, embedding FROM rag_chunks";
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                float[] storedVec = jsonToVector(rs.getString("embedding"));
                double score = embedder.cosineSimilarity(queryVec, storedVec);
                if (score >= minScore) {
                    results.add(new RAGChunk(
                        rs.getString("source"),
                        rs.getString("content"),
                        score
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[VectorStore] ❌ 检索失败: " + e.getMessage());
        }

        // 按相似度降序排列
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (results.size() > limit) results = results.subList(0, limit);
        return results;
    }

    /** 按 source 删除旧索引 */
    public void deleteBySource(String source) {
        String sql = "DELETE FROM rag_chunks WHERE source = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, source);
            int deleted = ps.executeUpdate();
            if (deleted > 0) System.out.println("[VectorStore] 删除旧索引: " + source + " (" + deleted + "条)");
        } catch (Exception e) {
            System.err.println("[VectorStore] ❌ 删除失败: " + e.getMessage());
        }
    }

    public int count() {
        String sql = "SELECT COUNT(*) FROM rag_chunks";
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (Exception ignored) {}
        return 0;
    }

    // ==================== 工具方法 ====================

    private String vectorToJson(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vec[i]));
        }
        return sb.append("]").toString();
    }

    private float[] jsonToVector(String json) {
        String[] parts = json.substring(1, json.length() - 1).split(",");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vec[i] = Float.parseFloat(parts[i].strip());
        }
        return vec;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbPath);
    }
}
