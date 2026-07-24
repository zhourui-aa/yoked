package org.example.bot.service;

/**
 * 网页内容抓取与摘要服务接口
 * 支持抓取微信公众号文章、新闻链接等网页，提取正文并总结要点
 */
public interface WebReaderService {

    /**
     * 抓取网页内容并返回格式化的正文文本
     * @param url 网页链接
     * @return 格式化的正文内容，如果抓取失败返回错误信息
     */
    String fetchContent(String url);

    /**
     * 抓取网页内容并总结要点
     * @param url 网页链接
     * @param aiService AI服务，用于总结文章
     * @param userId 用户ID（用于会话管理）
     * @return 文章摘要，如果抓取失败返回错误信息
     */
    String summarize(String url, AiService aiService, String userId);
}
