package org.example.bot.service;

/**
 * 联网搜索服务接口 — 基于 SerpAPI 实时搜索互联网。
 */
public interface WebSearchService {

    /**
     * 联网搜索。
     *
     * @param query 搜索关键词
     * @param num   返回结果数，默认 5
     * @return 格式化的搜索结果摘要，供 AI 二次消化
     */
    String search(String query, int num);
}
