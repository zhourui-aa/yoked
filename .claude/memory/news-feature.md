---
name: news-feature
description: 新闻查询功能 — RSS 实现、8 个类别、缓存追问
metadata:
  type: project
---

## 新闻查询功能

### 文件
- `service/NewsService.java` — 接口：getNews(category, count), getArticleDetail(query), getLastResults(), isAvailable()
- `impl/RssNewsServiceImpl.java` — 零 API Key 实现，JDK 内置 HTTP + XML

### 8 个类别对应 RSS 源
| 类别 | 来源 | 验证日期 |
|------|------|----------|
| 综合 | 中国新闻网 society | 2026-07-23 |
| 国际 | 中国新闻网 world | 2026-07-23 |
| 科技 | IT之家 ithome.com/rss | 2026-07-23 |
| 财经 | 中国新闻网 finance | 2026-07-23 |
| 体育 | 中国新闻网 sports | 2026-07-23 |
| 文化 | 中国新闻网 culture | 2026-07-23 |
| 健康 | 中国新闻网 health | 2026-07-23 |
| 教育 | 中国新闻网 edu | 2026-07-23 |

注意：娱乐专用 RSS (ent.xml) 为空频道，已移除。

### 缓存追问
- `get_news` 执行后结果缓存到 `LAST_NEWS` (Map<userId, CachedNews>)
- 缓存 5 分钟过期（复用 IMAGE_CACHE_TTL_MS）
- `read_news_article` 从缓存中按关键词/序号查找
- AI 不应编造新闻内容，只展示工具返回的真实内容

### FC 工具注册（buildTools 中）
- `get_news(category)` — 始终可用
- `read_news_article(query)` — news.isAvailable() 时可用

**Why:** 用户需要真实新闻且不能编造，RSS 零 API Key 降低门槛
**How to apply:** 添加新 RSS 源时改 RssNewsServiceImpl.DEFAULT_RSS_SOURCES Map
