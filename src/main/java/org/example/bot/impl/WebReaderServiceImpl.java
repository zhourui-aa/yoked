package org.example.bot.impl;

import org.example.bot.service.AiService;
import org.example.bot.service.WebReaderService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页内容抓取与摘要服务实现
 * 支持微信公众号文章、新闻链接等网页的正文提取和 AI 摘要
 */
public class WebReaderServiceImpl implements WebReaderService {

    public WebReaderServiceImpl() {
        System.out.println("[网页] 网页读取服务已就绪（文章抓取与摘要）");
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // 微信公众号文章正文选择器相关模式
    private static final Pattern WX_ARTICLE_TITLE = Pattern.compile(
            "<h1[^>]*class=[\"'](?:rich_media_title|article-title)[\"'][^>]*>(.*?)</h1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WX_ARTICLE_CONTENT = Pattern.compile(
            "<div[^>]*id=[\"']js_content[\"'][^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WX_ARTICLE_AUTHOR = Pattern.compile(
            "<span[^>]*class=[\"']rich_media_meta_text[\"'][^>]*>(.*?)</span>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // 通用新闻页面正文模式
    private static final Pattern META_TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CANONICAL_URL = Pattern.compile(
            "<link[^>]*rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    // HTML 标签清理
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MULTI_WHITESPACE = Pattern.compile("\\s+");

    // 获取页面编码
    private static final Pattern CHARSET_META = Pattern.compile(
            "<meta[^>]*charset=[\"']?([^\"'>]+)[\"']?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_TYPE_CHARSET = Pattern.compile(
            "charset=([^\\s;]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public String fetchContent(String url) {
        if (!isValidUrl(url)) {
            return "❌ 无效的链接格式，请提供完整的 URL（如：https://mp.weixin.qq.com/s/xxx）";
        }
        if (isPrivateUrl(url)) {
            return "❌ 不支持访问内网/本地地址。";
        }

        try {
            String html = fetchHtml(url);
            return extractContent(url, html);
        } catch (Exception e) {
            return "❌ 抓取失败：" + e.getMessage();
        }
    }

    /** 内网/本地地址拦截（SSRF 防护） */
    private boolean isPrivateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return true;
            String h = host.toLowerCase();
            // 直接本地地址
            if ("localhost".equals(h) || h.endsWith(".localhost")) return true;
            if (h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]")) return true;
            // 内网网段
            if (h.startsWith("10.") || h.startsWith("192.168.")
                || h.startsWith("172.16.") || h.startsWith("172.17.")
                || h.startsWith("172.18.") || h.startsWith("172.19.")
                || h.startsWith("172.20.") || h.startsWith("172.21.")
                || h.startsWith("172.22.") || h.startsWith("172.23.")
                || h.startsWith("172.24.") || h.startsWith("172.25.")
                || h.startsWith("172.26.") || h.startsWith("172.27.")
                || h.startsWith("172.28.") || h.startsWith("172.29.")
                || h.startsWith("172.30.") || h.startsWith("172.31.")) return true;
            // 0.0.0.0 / 169.254.x.x (链路本地)
            if (h.startsWith("0.0.0.0") || h.startsWith("169.254.")) return true;
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public String summarize(String url, AiService aiService, String userId) {
        String content = fetchContent(url);
        if (content.startsWith("❌")) {
            return content;
        }

        // 如果内容过长，截取前 3000 字用于摘要（避免超出 AI 上下文限制）
        String truncatedContent = content;
        if (content.length() > 3000) {
            truncatedContent = content.substring(0, 3000) + "\n\n（内容过长，已截取前3000字）";
        }

        // 使用 AI 进行摘要（chatDetached 不落史，避免把合成提示词污染真实对话上下文）
        String prompt = "请阅读以下网页内容，总结文章的核心要点（3-5条），包括主要观点、关键数据和结论。"
            + "直接根据以下正文内容总结即可，无需联网搜索：\n\n" + truncatedContent;
        String summary = aiService.chatDetached(userId, prompt);

        return "📰 文章摘要\n" +
               "━━━━━━━━━━━━━━━\n" +
               summary +
               "\n━━━━━━━━━━━━━━━\n" +
               "🔗 来源：" + url;
    }

    private boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private String fetchHtml(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", "https://www.google.com/")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP 状态码：" + response.statusCode());
        }

        // 读取 body（限制大小，防止坏 URL 撑爆内存）
        byte[] body;
        try (InputStream is = response.body()) {
            body = is.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("网页内容过大（超过 " + MAX_BODY_BYTES + " 字节），已中止抓取。");
        }

        // 检测编码：HTTP 头优先，其次 HTML meta charset，最后 UTF-8
        Charset charset = detectCharset(response.headers().firstValue("Content-Type").orElse(""), body);
        return new String(body, charset);
    }

    /** 响应体大小上限：5MB */
    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private Charset detectCharset(String contentType, byte[] body) {
        // 1. 从 HTTP 响应头获取
        Matcher matcher = CONTENT_TYPE_CHARSET.matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (Exception ignored) {}
        }
        // 2. 从 HTML <meta charset> 获取（GBK/GB2312 页面关键）
        try {
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher metaMatcher = CHARSET_META.matcher(head);
            if (metaMatcher.find()) {
                return Charset.forName(metaMatcher.group(1).strip());
            }
        } catch (Exception ignored) {}
        return StandardCharsets.UTF_8;
    }

    private String extractContent(String url, String html) {
        // 去除脚本和样式
        html = SCRIPT_STYLE.matcher(html).replaceAll("");

        StringBuilder result = new StringBuilder();

        // 尝试提取标题
        String title = extractTitle(html);
        if (!title.isEmpty()) {
            result.append("📝 ").append(title).append("\n\n");
        }

        // 尝试提取来源信息
        if (url.contains("mp.weixin.qq.com")) {
            // 微信公众号文章
            String author = extractWxAuthor(html);
            if (!author.isEmpty()) {
                result.append("👤 ").append(author).append("\n\n");
            }
        }

        // 提取正文
        String content = extractBody(url, html);
        if (!content.isEmpty()) {
            result.append(content);
        } else {
            // 兜底：提取所有可见文本
            content = cleanText(html);
            if (!content.isEmpty() && content.length() > 100) {
                result.append(content);
            } else {
                return "❌ 无法提取文章正文，该网页可能需要登录或内容为动态加载";
            }
        }

        return result.toString().trim();
    }

    private String extractTitle(String html) {
        Matcher matcher = META_TITLE.matcher(html);
        if (matcher.find()) {
            String title = cleanText(matcher.group(1));
            // 去除常见后缀
            title = title.replaceAll("[-_|·].*?(微信|公众号|新浪|网易|腾讯|搜狐|新闻)$", "");
            return title.trim();
        }
        return "";
    }

    private String extractWxAuthor(String html) {
        Matcher matcher = WX_ARTICLE_AUTHOR.matcher(html);
        if (matcher.find()) {
            return cleanText(matcher.group(1)).trim();
        }
        return "";
    }

    private String extractBody(String url, String html) {
        if (url.contains("mp.weixin.qq.com")) {
            // 微信公众号文章
            Matcher matcher = WX_ARTICLE_CONTENT.matcher(html);
            if (matcher.find()) {
                return cleanHtmlContent(matcher.group(1));
            }
        }

        // 通用新闻页面
        // 尝试常见的正文容器
        String[] selectors = {
                "<article[^>]*>(.*?)</article>",
                "<div[^>]*class=[\"']?(?:article-content|content|main-content|post-content|articleBody)[\"']?[^>]*>(.*?)</div>",
                "<div[^>]*id=[\"']?(?:content|article|main)[\"']?[^>]*>(.*?)</div>",
                "<div[^>]*class=[\"']?(?:rich_media_content|page_content)[\"']?[^>]*>(.*?)</div>"
        };

        for (String selector : selectors) {
            Pattern pattern = Pattern.compile(selector, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String content = cleanHtmlContent(matcher.group(1));
                if (content.length() > 100) {
                    return content;
                }
            }
        }

        return "";
    }

    private String cleanHtmlContent(String html) {
        // 去除 HTML 标签
        String text = HTML_TAGS.matcher(html).replaceAll("");
        // 清理空白字符
        text = MULTI_WHITESPACE.matcher(text).replaceAll(" ");
        // 去除多余的空格和换行
        text = text.replace("  ", "\n\n")
                   .replace("\n\n\n", "\n\n")
                   .trim();
        return text;
    }

    private String cleanText(String text) {
        if (text == null) return "";
        // 去除 HTML 实体
        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'");
        // 清理空白字符
        text = MULTI_WHITESPACE.matcher(text).replaceAll(" ").trim();
        return text;
    }
}
