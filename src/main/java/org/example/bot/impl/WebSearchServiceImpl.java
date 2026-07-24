package org.example.bot.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.bot.service.WebSearchService;
import org.example.bot.util.ConfigUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联网搜索服务实现 — 基于 SerpAPI（Google 搜索结果）。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code serpapi.api.key} / {@code SERPAPI_API_KEY} — SerpAPI 密钥</li>
 * </ul>
 *
 * <p>免费额度 100 次/月，注册 https://serpapi.com/ 。
 */
public class WebSearchServiceImpl implements WebSearchService {

    private static final String API_URL = "https://serpapi.com/search";
    private static final int DEFAULT_NUM = 5;
    private static final int MAX_NUM = 10;
    private static final long CACHE_TTL = 5 * 60 * 1000;

    private final String apiKey;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();

    public WebSearchServiceImpl() {
        String key = ConfigUtil.get("serpapi.api.key", "SERPAPI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "未找到 SerpAPI Key。请在 config.properties 中设置 serpapi.api.key。\n"
                    + "获取方式：https://serpapi.com/ 注册后获取（100次/月免费）。");
        }
        this.apiKey = key.strip();
        System.out.println("[搜索] 联网搜索服务已就绪（SerpAPI，100次/月免费）");
    }

    @Override
    public String search(String query, int num) {
        if (query == null || query.isBlank()) {
            return "请提供搜索关键词。";
        }
        int n = num > 0 && num <= MAX_NUM ? num : DEFAULT_NUM;
        String q = query.strip();

        try {
            String url = API_URL + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&num=" + n + "&api_key=" + apiKey
                    + "&gl=cn&hl=zh-CN&output=json";

            String json = fetchUrl(url);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // 检查 API 错误
            if (root.has("error")) {
                return "搜索失败：" + root.get("error").getAsString();
            }

            // 解析知识图谱
            StringBuilder sb = new StringBuilder();
            if (root.has("knowledge_graph") && root.get("knowledge_graph").isJsonObject()) {
                JsonObject kg = root.getAsJsonObject("knowledge_graph");
                if (kg.has("title")) sb.append("📌 ").append(kg.get("title").getAsString()).append("\n");
                if (kg.has("description")) sb.append(kg.get("description").getAsString()).append("\n\n");
            }

            // 解析有机结果
            JsonArray organic = root.has("organic_results")
                    ? root.getAsJsonArray("organic_results") : new JsonArray();
            if (organic.size() > 0) {
                sb.append("🔍 搜索结果（").append(organic.size()).append("条）：\n\n");
                for (int i = 0; i < organic.size(); i++) {
                    JsonObject r = organic.get(i).getAsJsonObject();
                    int idx = i + 1;
                    String title = r.has("title") ? r.get("title").getAsString() : "";
                    String snippet = r.has("snippet") ? r.get("snippet").getAsString() : "";
                    String link = r.has("link") ? r.get("link").getAsString() : "";
                    sb.append(idx).append(". **").append(title).append("**\n");
                    sb.append("   ").append(snippet).append("\n");
                    if (!link.isBlank()) sb.append("   🔗 ").append(link).append("\n");
                    sb.append("\n");
                }
            } else {
                sb.append("未找到相关结果。");
            }

            // 解析相关问题
            if (root.has("related_questions") && root.get("related_questions").isJsonArray()) {
                JsonArray related = root.getAsJsonArray("related_questions");
                if (related.size() > 0) {
                    sb.append("💡 相关问题：\n");
                    for (int i = 0; i < Math.min(3, related.size()); i++) {
                        JsonObject rq = related.get(i).getAsJsonObject();
                        if (rq.has("question")) {
                            sb.append("  • ").append(rq.get("question").getAsString()).append("\n");
                        }
                    }
                }
            }

            return sb.toString().strip();
        } catch (Exception e) {
            return "联网搜索失败：" + e.getMessage();
        }
    }

    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private record CacheEntry(String result, long timestamp) {
        boolean expired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }
    }
}
