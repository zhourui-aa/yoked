package org.example.bot.rag;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 文档检索器 — 索引 docs/ 目录下的 .txt/.md 文件，支持向量语义检索。
 *
 * <h3>使用</h3>
 * <pre>
 *   // 启动时建索引
 *   DocumentRetriever docRetriever = new DocumentRetriever(vectorStore);
 *   docRetriever.indexDirectory("docs");
 *
 *   // 检索时
 *   List&lt;RAGChunk&gt; results = docRetriever.retrieve(userId, "退货政策", 3);
 * </pre>
 */
public class DocumentRetriever implements RAGRetriever {

    private final VectorStore store;
    private final int chunkSize;  // 每个分片的字符数

    public DocumentRetriever(VectorStore store) {
        this(store, 500);
    }

    public DocumentRetriever(VectorStore store, int chunkSize) {
        this.store = store;
        this.chunkSize = chunkSize;
    }

    @Override
    public String name() { return "document"; }

    /** 索引目录下所有 .txt/.md/.csv 文件 */
    public int indexDirectory(String dirPath) {
        Path dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) {
            System.out.println("[DocumentRetriever] ⚠ 目录不存在: " + dirPath);
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{txt,md,csv}")) {
            for (Path p : stream) {
                try {
                    String source = p.getFileName().toString();
                    store.deleteBySource(source); // 删旧
                    String text = Files.readString(p);
                    List<String> chunks = chunkText(text);
                    store.insertAll(source, chunks);
                    count += chunks.size();
                } catch (IOException e) {
                    System.err.println("[DocumentRetriever] ⚠ 读文件失败: " + p);
                }
            }
        } catch (IOException e) {
            System.err.println("[DocumentRetriever] ❌ 扫描目录失败: " + e.getMessage());
        }
        System.out.println("[DocumentRetriever] 索引完成: " + dirPath + " → " + count + " 个分片");
        return count;
    }

    @Override
    public List<RAGChunk> retrieve(String userId, String query, int maxResults) {
        if (query == null || query.isBlank()) return List.of();
        // 检测是否触发文档检索（问题性质，非闲聊）
        if (!looksLikeQuestion(query)) return List.of();
        return store.search(query, maxResults, 0.15);
    }

    /** 判断是否像在问知识类问题 */
    private boolean looksLikeQuestion(String text) {
        String[] markers = {"什么", "怎么", "如何", "什么是", "为什么",
                           "能不能", "可以", "有没有", "多少", "哪个", "哪里",
                           "政策", "规定", "流程", "说明", "文档", "手册"};
        for (String m : markers) {
            if (text.contains(m)) return true;
        }
        return false;
    }

    /** 将长文本按句子边界切成固定大小的分片 */
    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        // 先按段落切
        String[] paragraphs = text.split("\n\n|\\n\\s*\\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            para = para.strip();
            if (para.isEmpty()) continue;

            if (current.length() + para.length() > chunkSize && current.length() > 0) {
                chunks.add(current.toString().strip());
                current.setLength(0);
            }
            if (current.length() > 0) current.append("\n");
            current.append(para);
        }
        if (current.length() > 0) chunks.add(current.toString().strip());

        return chunks;
    }
}
