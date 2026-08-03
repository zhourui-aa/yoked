package org.example.bot.impl;

import org.example.bot.service.NewsService;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 基于 RSS 源的新闻服务实现 — 零 API Key 依赖。
 */
public class RssNewsServiceImpl implements NewsService {

    private static final Map<String, String> DEFAULT_RSS_SOURCES = new LinkedHashMap<>();
    static {
        DEFAULT_RSS_SOURCES.put("综合", "https://www.chinanews.com.cn/rss/society.xml");
        DEFAULT_RSS_SOURCES.put("国际", "https://www.chinanews.com.cn/rss/world.xml");
        DEFAULT_RSS_SOURCES.put("科技", "https://www.ithome.com/rss/");
        DEFAULT_RSS_SOURCES.put("财经", "https://www.chinanews.com.cn/rss/finance.xml");
        DEFAULT_RSS_SOURCES.put("体育", "https://www.chinanews.com.cn/rss/sports.xml");
        DEFAULT_RSS_SOURCES.put("文化", "https://www.chinanews.com.cn/rss/culture.xml");
        DEFAULT_RSS_SOURCES.put("健康", "https://www.chinanews.com.cn/rss/health.xml");
        DEFAULT_RSS_SOURCES.put("教育", "https://www.chinanews.com.cn/rss/edu.xml");
    }

    private final HttpClient httpClient;
    private final List<NewsItem> lastResults = new ArrayList<>();

    public RssNewsServiceImpl() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        System.out.println("[新闻] RSS 新闻服务已就绪（类别：" + DEFAULT_RSS_SOURCES.size() + " 个）");
    }

    @Override
    public String getNews(String category, int count) {
        lastResults.clear();

        String url = DEFAULT_RSS_SOURCES.getOrDefault(category, DEFAULT_RSS_SOURCES.get("综合"));
        System.out.println("[新闻] 获取 " + category + " 新闻: " + url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (compatible; WeChatBot/1.0)")
                    .GET().build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                return "新闻源返回错误（HTTP " + response.statusCode() + "），请稍后再试。";
            }

            byte[] body = response.body();
            String xml = decodeResponse(body, response.headers()
                    .firstValue("Content-Type").orElse(""));

            List<NewsItem> items = parseRss(xml);
            if (items.isEmpty()) {
                return "当前没有 " + category + " 类别的新闻。";
            }

            int limit = Math.min(count, items.size());
            lastResults.addAll(items.subList(0, limit));

            StringBuilder sb = new StringBuilder();
            sb.append("【").append(category).append("新闻】最新 ").append(limit).append(" 条：\n\n");
            for (int i = 0; i < limit; i++) {
                NewsItem item = items.get(i);
                sb.append(i + 1).append(". ").append(item.title()).append("\n");
                if (item.description() != null && !item.description().isBlank()) {
                    String desc = item.description().replaceAll("<[^>]+>", "")
                            .replaceAll("&\\w+;", " ");
                    if (desc.length() > 80) desc = desc.substring(0, 80) + "…";
                    sb.append("   ").append(desc).append("\n");
                }
                sb.append("\n");
            }
            sb.append("💡 你可以说「第X条详细说说」来了解某条新闻。");
            return sb.toString();
        } catch (Exception e) {
            System.err.println("[新闻] ❌ 获取失败: " + e.getMessage());
            return "获取新闻失败：" + e.getMessage();
        }
    }

    @Override
    public String getArticleDetail(String query) {
        if (lastResults.isEmpty()) return "请先查询新闻。";

        NewsItem item = findItem(query);
        if (item == null) return "未找到包含「" + query + "」的新闻。请确认标题或序号是否正确。";
        return formatArticle(item);
    }

    private NewsItem findItem(String query) {
        try {
            int index = Integer.parseInt(query.replaceAll("[^0-9]", ""));
            if (index >= 1 && index <= lastResults.size())
                return lastResults.get(index - 1);
        } catch (NumberFormatException ignored) {}
        for (NewsItem item : lastResults) {
            if (item.title().contains(query)) return item;
        }
        return null;
    }

    @Override
    public List<NewsItem> getLastResults() {
        return new ArrayList<>(lastResults);
    }

    // ---- 内部方法 ----

    private List<NewsItem> parseRss(String xml) throws Exception {
        List<NewsItem> items = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList itemNodes = doc.getElementsByTagName("item");

        for (int i = 0; i < itemNodes.getLength(); i++) {
            Element elem = (Element) itemNodes.item(i);
            String title = getChildText(elem, "title");
            String desc = getChildText(elem, "description");
            String link = getChildText(elem, "link");
            if (title != null && !title.isBlank()) {
                List<String> imageUrls = extractImageUrls(desc);
                items.add(new NewsItem(title.strip(),
                        desc != null ? desc.strip() : "",
                        link != null ? link.strip() : "",
                        imageUrls));
            }
        }
        return items;
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0) return list.item(0).getTextContent();
        return null;
    }

    private static String decodeResponse(byte[] body, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        if (contentType.contains("charset=")) {
            try {
                String cs = contentType.replaceAll(".*charset=([^;]+).*", "$1").strip();
                charset = Charset.forName(cs);
            } catch (Exception ignored) {}
        }
        try {
            String s = new String(body, charset);
            if (s.contains("<?xml") || s.contains("<rss")) return s;
        } catch (Exception ignored) {}
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String formatArticle(NewsItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("📰 ").append(item.title()).append("\n\n");
        if (item.description() != null && !item.description().isBlank()) {
            String desc = item.description().replaceAll("<[^>]+>", "")
                    .replaceAll("&\\w+;", " ");
            if (desc.length() > 800) desc = desc.substring(0, 800) + "…";
            sb.append(desc).append("\n\n");
        }
        if (item.imageUrls() != null && !item.imageUrls().isEmpty()) {
            sb.append("🖼 图片：").append(item.imageUrls().size()).append(" 张\n");
        }
        if (item.link() != null && !item.link().isBlank()) {
            sb.append("🔗 ").append(item.link());
        }
        return sb.toString();
    }

    // ---- 图片提取（仅从 RSS 描述中提取，IT之家有图） ----

    static List<String> extractImageUrls(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<String> urls = new ArrayList<>();
        Pattern p = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            String url = m.group(1);
            if (!isVideo(url)) urls.add(resolveUrl(url));
        }
        return urls.stream().distinct().toList();
    }

    private static String resolveUrl(String url) {
        if (url.startsWith("//")) return "https:" + url;
        return url;
    }

    private static boolean isVideo(String url) {
        String lower = url.toLowerCase();
        return lower.contains("youtube") || lower.contains("youtu.be")
            || lower.contains("vimeo") || lower.contains("bilibili")
            || lower.contains(".mp4") || lower.contains(".webm")
            || lower.contains(".avi") || lower.contains(".mov")
            || lower.contains("video") || lower.contains("/video/");
    }
}
