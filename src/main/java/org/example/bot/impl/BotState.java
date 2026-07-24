package org.example.bot.impl;

import org.example.bot.service.NewsService;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的 per-user 状态缓存（图片、文档、新闻结果）。
 *
 * <p>替代 BotApp 中旧的 {@code HashMap} 缓存，使用 {@link ConcurrentHashMap}
 * 提供 happens-before 可见性保证，消除并发读写的数据竞争。
 */
public class BotState {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    /** 通用缓存条目 — 替代旧的三组几乎相同的内部类 */
    private record CacheEntry<T>(T value, long timestamp) {
        boolean expired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    /** 文档缓存快照 */
    public record DocSnapshot(String content, String fileName) {}

    // ---- 三个 ConcurrentHashMap 缓存 ----
    private final ConcurrentHashMap<String, CacheEntry<byte[]>> imageCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<DocSnapshot>> docCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<NewsService.NewsItem>>> newsCache = new ConcurrentHashMap<>();

    // ========== 图片缓存 ==========

    public void putImage(String userId, byte[] bytes) {
        imageCache.put(userId, new CacheEntry<>(bytes, System.currentTimeMillis()));
    }

    /** @return 图片字节，不存在或已过期返回 {@code null} */
    public byte[] getImage(String userId) {
        CacheEntry<byte[]> e = imageCache.get(userId);
        if (e == null) return null;
        if (e.expired()) { imageCache.remove(userId); return null; }
        return e.value();
    }

    public boolean isImageAvailable(String userId) {
        return getImage(userId) != null;
    }

    // ========== 文档缓存 ==========

    public void putDoc(String userId, String content, String fileName) {
        docCache.put(userId, new CacheEntry<>(new DocSnapshot(content, fileName),
                System.currentTimeMillis()));
    }

    /** @return 文档快照，不存在或已过期返回 {@code null} */
    public DocSnapshot getDoc(String userId) {
        CacheEntry<DocSnapshot> e = docCache.get(userId);
        if (e == null) return null;
        if (e.expired()) { docCache.remove(userId); return null; }
        return e.value();
    }

    public boolean isDocAvailable(String userId) {
        return getDoc(userId) != null;
    }

    // ========== 新闻缓存 ==========

    public void putNews(String userId, List<NewsService.NewsItem> items) {
        newsCache.put(userId, new CacheEntry<>(items, System.currentTimeMillis()));
    }

    /** @return 新闻条目列表，不存在或已过期返回 {@code null} */
    public List<NewsService.NewsItem> getNews(String userId) {
        CacheEntry<List<NewsService.NewsItem>> e = newsCache.get(userId);
        if (e == null) return null;
        if (e.expired()) { newsCache.remove(userId); return null; }
        return e.value();
    }

    public boolean isNewsAvailable(String userId) {
        return getNews(userId) != null;
    }
}
