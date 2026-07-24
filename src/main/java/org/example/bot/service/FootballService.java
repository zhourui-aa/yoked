package org.example.bot.service;

/**
 * 足球数据服务接口 — 英超积分榜、比赛、转会新闻查询。
 *
 * <p>数据来源：
 * <ul>
 *   <li>比赛数据：openfootball/football.json（GitHub 免费开放数据）</li>
 *   <li>新闻资讯：懂球帝搜索 API</li>
 * </ul>
 */
public interface FootballService {

    /**
     * 查询英超积分榜。
     *
     * @param topN 返回前 N 名，传 0 表示全部
     * @return 格式化的积分榜文本
     */
    String getStandings(int topN);

    /**
     * 查询最近已结束的比赛。
     *
     * @param count 返回场次数
     * @return 格式化的比赛结果文本
     */
    String getRecentMatches(int count);

    /**
     * 查询即将进行的比赛。
     *
     * @param count 返回场次数
     * @return 格式化的赛程文本
     */
    String getUpcomingMatches(int count);

    /**
     * 搜索懂球帝足球新闻（转会、赛事等）。
     *
     * @param keyword 搜索关键词
     * @return 格式化的新闻列表
     */
    String searchNews(String keyword);

    /**
     * 按轮次查询比赛结果。
     *
     * @param matchday 轮次（如 "Matchday 1"）
     * @return 格式化的该轮比赛结果
     */
    String getMatchdayResults(String matchday);
}
