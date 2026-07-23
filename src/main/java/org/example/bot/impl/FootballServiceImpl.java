package org.example.bot.impl;

import com.google.gson.*;
import org.example.bot.service.FootballService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 足球数据服务实现 — 英超积分榜 + 比赛查询 + 懂球帝新闻。
 *
 * <p>比赛数据来源：openfootball/football.json（GitHub 开放数据，2025/26 赛季 380 场）
 * <p>数据拉取策略：本地缓存 → jsDelivr CDN → GitHub Raw（国内友好）
 * <p>新闻来源：懂球帝搜索 API
 */
public class FootballServiceImpl implements FootballService {

    /** CDN 加速（国内可访问） */
    private static final String DATA_URL_CDN =
        "https://cdn.jsdelivr.net/gh/openfootball/football.json@master/2025-26/en.1.json";
    /** GitHub 直连（备用） */
    private static final String DATA_URL_GITHUB =
        "https://raw.githubusercontent.com/openfootball/football.json/master/2025-26/en.1.json";
    /** 懂球帝搜索 */
    private static final String DONGQIUDI_SEARCH = "https://dongqiudi.com/api/search?keyword=";
    /** 网络超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** 内存缓存有效期 30 分钟 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000;
    /** 本地缓存文件名 */
    private static final Path LOCAL_CACHE = Paths.get("football_data.json");

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private List<Match> cachedMatches;
    private Map<String, TeamStats> cachedStandings;
    private long lastFetchTime;
    private String lastFetchSource = "无";

    public FootballServiceImpl() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        System.out.println("[足球] 英超数据服务已就绪（CDN + 本地缓存 + 懂球帝）");
    }

    // ==================== 积分榜 ====================

    @Override
    public String getStandings(int topN) {
        Map<String, TeamStats> standings = getOrComputeStandings();
        if (standings.isEmpty()) return "⚠ 积分榜数据暂不可用，请稍后再试。";

        List<TeamStats> sorted = new ArrayList<>(standings.values());
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(b.getPoints(), a.getPoints());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(b.getGoalDiff(), a.getGoalDiff());
            if (cmp != 0) return cmp;
            return Integer.compare(b.goalsFor, a.goalsFor);
        });

        if (topN > 0 && topN < sorted.size()) sorted = sorted.subList(0, topN);

        StringBuilder sb = new StringBuilder("🏆 英超 2025/26 积分榜\n\n");
        sb.append(String.format("%-4s %-24s %3s %3s %3s %3s %3s %3s %4s %4s\n",
                "排名", "球队", "场", "胜", "平", "负", "进", "失", "净", "分"));
        sb.append("─".repeat(72)).append("\n");

        int rank = 1;
        for (TeamStats t : sorted) {
            String icon = rank <= 4 ? rank == 1 ? "🥇" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : "🏅" : "  ";
            sb.append(String.format("%s%-2d %-24s %2d %2d %2d %2d %3d %3d %+4d %4d\n",
                    icon, rank, t.name, t.played, t.wins, t.draws, t.losses,
                    t.goalsFor, t.goalsAgainst, t.getGoalDiff(), t.getPoints()));
            rank++;
        }

        sb.append("\n🥇=欧冠  🥈=欧冠  🥉=欧冠  🏅=欧冠资格");
        if (!"无".equals(lastFetchSource)) sb.append("\n📡 数据源: ").append(lastFetchSource);
        return sb.toString();
    }

    // ==================== 最近比赛 ====================

    @Override
    public String getRecentMatches(int count) {
        List<Match> matches = getCachedMatches();
        if (matches.isEmpty()) return "⚠ 比赛数据暂不可用，请稍后再试。";

        LocalDate today = LocalDate.now();
        List<Match> finished = matches.stream()
                .filter(m -> m.isFinished())
                .filter(m -> !m.date.isAfter(today))
                .sorted(Comparator.comparing((Match m) -> m.date).reversed())
                .collect(Collectors.toList());

        if (count > 0 && count < finished.size()) finished = finished.subList(0, count);

        return formatMatchList("📺 英超最近比赛", finished);
    }

    // ==================== 即将比赛 ====================

    @Override
    public String getUpcomingMatches(int count) {
        List<Match> matches = getCachedMatches();
        if (matches.isEmpty()) return "⚠ 赛程数据暂不可用，请稍后再试。";

        LocalDate today = LocalDate.now();
        List<Match> upcoming = matches.stream()
                .filter(m -> !m.isFinished())
                .filter(m -> m.date.isAfter(today) || m.date.isEqual(today))
                .sorted(Comparator.comparing((Match m) -> m.date).thenComparing(m -> m.time))
                .collect(Collectors.toList());

        if (upcoming.isEmpty()) {
            return "⚽ 2025/26 赛季已全部结束。\n"
                + "请期待 2026/27 新赛季（通常 8 月开赛）！\n"
                + "可以发送「英超积分榜」查看最终排名。";
        }

        if (count > 0 && count < upcoming.size()) upcoming = upcoming.subList(0, count);

        return formatMatchList("📅 英超即将进行的比赛", upcoming);
    }

    // ==================== 轮次查询 ====================

    @Override
    public String getMatchdayResults(String matchday) {
        List<Match> matches = getCachedMatches();
        if (matches.isEmpty()) return "⚠ 比赛数据暂不可用。";

        String search = matchday.toLowerCase().contains("matchday")
            ? matchday : "Matchday " + matchday.trim();

        List<Match> round = matches.stream()
                .filter(m -> m.round.equalsIgnoreCase(search))
                .sorted(Comparator.comparing((Match m) -> m.date).thenComparing(m -> m.time))
                .collect(Collectors.toList());

        if (round.isEmpty()) return "未找到「" + matchday + "」的比赛数据。";

        return formatMatchList("📋 " + round.get(0).round + " 赛果", round);
    }

    // ==================== 新闻搜索 ====================

    @Override
    public String searchNews(String keyword) {
        try {
            String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String url = DONGQIUDI_SEARCH + encoded;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "懂球帝搜索暂不可用（HTTP " + resp.statusCode() + "）";

            JsonObject json = gson.fromJson(resp.body(), JsonObject.class);
            if (json.get("code").getAsInt() != 0) return "懂球帝搜索暂不可用。";

            JsonObject data = json.getAsJsonObject("data");
            StringBuilder sb = new StringBuilder("🔍 懂球帝搜索：「" + keyword + "」\n\n");

            int found = appendSearchResults(sb, data, "news", "📰 新闻");
            found += appendSearchResults(sb, data, "topics", "💬 话题");
            found += appendSearchResults(sb, data, "teams", "⚽ 球队");
            found += appendSearchResults(sb, data, "players", "👤 球员");

            if (found == 0) {
                sb.append("未找到相关内容。\n");
                sb.append("💡 提示：试试「英超」「转会」「利物浦」等关键词。\n");
                sb.append("或访问：https://www.dongqiudi.com 获取最新资讯。");
            }

            return sb.toString();

        } catch (Exception e) {
            System.err.println("[足球] 懂球帝搜索失败: " + e.getMessage());
            return "⚠ 懂球帝搜索暂不可用。\n"
                + "💡 请直接访问 https://www.dongqiudi.com 查看最新资讯。";
        }
    }

    // ==================== 核心：多源数据拉取 + 本地缓存 ====================

    /** 获取比赛数据（内存 → 本地文件 → CDN → GitHub） */
    private List<Match> getCachedMatches() {
        // 1. 内存缓存有效，直接返回
        if (cachedMatches != null && System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS) {
            return cachedMatches;
        }

        String raw = null;

        // 2. 尝试从本地文件加载（持久化缓存，跨重启有效）
        raw = loadLocalCache();
        if (raw != null) {
            cachedMatches = parseMatches(raw);
            cachedStandings = null;
            lastFetchTime = System.currentTimeMillis();
            lastFetchSource = "本地缓存";
            System.out.println("[足球] 数据已加载(本地): " + cachedMatches.size() + " 场比赛");
            return cachedMatches;
        }

        // 3. 尝试从 CDN 拉取（国内友好）
        raw = fetchFromUrl(DATA_URL_CDN, "CDN");
        if (raw == null) {
            // 4. 回退到 GitHub 直连
            raw = fetchFromUrl(DATA_URL_GITHUB, "GitHub");
        }

        if (raw != null) {
            cachedMatches = parseMatches(raw);
            cachedStandings = null;
            lastFetchTime = System.currentTimeMillis();
            // 保存到本地，下次启动直接用
            saveLocalCache(raw);
            System.out.println("[足球] 数据已刷新: " + cachedMatches.size() + " 场比赛");
            return cachedMatches;
        }

        // 5. 全部失败
        System.err.println("[足球] ❌ 所有数据源均不可用，请检查网络");
        if (cachedMatches == null) cachedMatches = Collections.emptyList();
        lastFetchSource = "离线（无缓存）";
        return cachedMatches;
    }

    /** 从指定 URL 拉取数据，失败返回 null */
    private String fetchFromUrl(String url, String label) {
        try {
            System.out.println("[足球] 尝试从 " + label + " 拉取数据...");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Java-HttpClient")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                lastFetchSource = label;
                return resp.body();
            }
            System.err.println("[足球] " + label + " 返回 HTTP " + resp.statusCode());
        } catch (Exception e) {
            System.err.println("[足球] " + label + " 失败: " + e.getMessage());
        }
        return null;
    }

    // ---- 本地文件缓存 ----

    private String loadLocalCache() {
        try {
            if (Files.exists(LOCAL_CACHE)) {
                return Files.readString(LOCAL_CACHE, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[足球] 读取本地缓存失败: " + e.getMessage());
        }
        return null;
    }

    private void saveLocalCache(String json) {
        try {
            Files.writeString(LOCAL_CACHE, json, StandardCharsets.UTF_8);
            System.out.println("[足球] 已保存本地缓存: " + LOCAL_CACHE.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[足球] 保存本地缓存失败: " + e.getMessage());
        }
    }

    // ---- JSON 解析 ----

    private List<Match> parseMatches(String raw) {
        JsonObject root = gson.fromJson(raw, JsonObject.class);
        JsonArray arr = root.getAsJsonArray("matches");

        List<Match> matches = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (JsonElement e : arr) {
            JsonObject m = e.getAsJsonObject();
            String round = m.get("round").getAsString();
            LocalDate date = LocalDate.parse(m.get("date").getAsString(), fmt);
            String time = m.has("time") ? m.get("time").getAsString() : "";
            String team1 = m.get("team1").getAsString();
            String team2 = m.get("team2").getAsString();

            int score1 = -1, score2 = -1;
            boolean finished = false;

            JsonElement scoreElem = m.get("score");
            if (scoreElem != null && scoreElem.isJsonObject()) {
                JsonObject scoreObj = scoreElem.getAsJsonObject();
                if (scoreObj.has("ft")) {
                    JsonArray ft = scoreObj.getAsJsonArray("ft");
                    score1 = ft.get(0).getAsInt();
                    score2 = ft.get(1).getAsInt();
                    finished = true;
                }
            } else if (scoreElem != null && scoreElem.isJsonArray()) {
                JsonArray arr2 = scoreElem.getAsJsonArray();
                if (date.isBefore(LocalDate.now()) || date.isEqual(LocalDate.now())) {
                    score1 = arr2.get(0).getAsInt();
                    score2 = arr2.get(1).getAsInt();
                    finished = true;
                }
            }

            matches.add(new Match(round, date, time, team1, team2, score1, score2, finished));
        }

        return matches;
    }

    // ---- 积分榜计算 ----

    private Map<String, TeamStats> getOrComputeStandings() {
        if (cachedStandings != null && System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS) {
            return cachedStandings;
        }
        List<Match> matches = getCachedMatches();
        cachedStandings = computeStandings(matches);
        return cachedStandings;
    }

    private Map<String, TeamStats> computeStandings(List<Match> matches) {
        Map<String, TeamStats> map = new LinkedHashMap<>();

        for (Match m : matches) {
            if (!m.isFinished()) continue;

            map.putIfAbsent(m.team1, new TeamStats(m.team1));
            map.putIfAbsent(m.team2, new TeamStats(m.team2));

            TeamStats t1 = map.get(m.team1);
            TeamStats t2 = map.get(m.team2);

            t1.played++; t2.played++;
            t1.goalsFor += m.score1; t1.goalsAgainst += m.score2;
            t2.goalsFor += m.score2; t2.goalsAgainst += m.score1;

            if (m.score1 > m.score2) { t1.wins++; t2.losses++; }
            else if (m.score1 < m.score2) { t2.wins++; t1.losses++; }
            else { t1.draws++; t2.draws++; }
        }

        return map;
    }

    // ---- 格式化输出 ----

    private String formatMatchList(String title, List<Match> matches) {
        if (matches.isEmpty()) return title + "\n\n暂无比赛数据。";

        StringBuilder sb = new StringBuilder(title + "\n\n");
        String lastRound = "";
        for (Match m : matches) {
            if (!m.round.equals(lastRound)) {
                sb.append("【").append(m.round).append("】\n");
                lastRound = m.round;
            }
            sb.append(formatMatch(m)).append("\n");
        }
        return sb.toString();
    }

    private String formatMatch(Match m) {
        String dateStr = m.date.format(DateTimeFormatter.ofPattern("MM/dd"));
        String result = m.isFinished() ? m.score1 + " - " + m.score2 : "vs";
        return String.format("  %s %s  %s  %s  %s", dateStr, m.time, m.team1, result, m.team2);
    }

    private int appendSearchResults(StringBuilder sb, JsonObject data, String key, String label) {
        JsonArray arr = data.getAsJsonArray(key);
        if (arr == null || arr.isEmpty()) return 0;

        sb.append(label).append("：\n");
        int count = 0;
        for (JsonElement e : arr) {
            if (count >= 5) break;
            JsonObject item = e.getAsJsonObject();
            String title = item.has("title") ? item.get("title").getAsString()
                    : item.has("name") ? item.get("name").getAsString() : "";
            String url = item.has("url") ? item.get("url").getAsString() : "";
            if (!title.isEmpty()) {
                sb.append("  • ").append(title);
                if (!url.isEmpty()) sb.append(" — ").append(url);
                sb.append("\n");
                count++;
            }
        }
        return count;
    }

    // ==================== 内部数据类 ====================

    private static class Match {
        final String round, time, team1, team2;
        final LocalDate date;
        final int score1, score2;
        final boolean finished;

        Match(String round, LocalDate date, String time, String team1, String team2,
              int score1, int score2, boolean finished) {
            this.round = round; this.date = date; this.time = time;
            this.team1 = team1; this.team2 = team2;
            this.score1 = score1; this.score2 = score2;
            this.finished = finished;
        }

        boolean isFinished() { return finished; }
    }

    static class TeamStats {
        final String name;
        int played, wins, draws, losses, goalsFor, goalsAgainst;

        TeamStats(String name) { this.name = name; }

        int getGoalDiff() { return goalsFor - goalsAgainst; }
        int getPoints() { return wins * 3 + draws; }
    }
}
