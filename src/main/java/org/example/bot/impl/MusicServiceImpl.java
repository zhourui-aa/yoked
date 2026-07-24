package org.example.bot.impl;

import org.example.bot.service.MusicService;
import org.example.bot.util.ConfigUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class MusicServiceImpl implements MusicService {

    private final String baseUrl;
    private final String apiKey;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public MusicServiceImpl() {
        String url = ConfigUtil.get("music.api.url", "MUSIC_API_URL");
        this.apiKey = ConfigUtil.get("music.api.key", "MUSIC_API_KEY");

        if (url == null || url.isBlank()) {
            this.baseUrl = "https://api.jimsdeng.eu.org";
            System.out.println("[音乐] ⚠ 未配置 music.api.url，使用默认 API");
        } else {
            this.baseUrl = url.strip();
            System.out.println("[音乐] ✅ 音乐服务已就绪（API: " + baseUrl + "）");
        }
    }

    private String searchUrl(String keyword) {
        // NeteaseCloudMusicApi 格式：/search?keywords=xxx
        return baseUrl + "/search?keywords=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    private String songUrl(long songId) {
        // NeteaseCloudMusicApi 格式：/song/url/v1?id=xxx&level=standard
        return baseUrl + "/song/url/v1?id=" + songId + "&level=standard";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String search(String songName, String artist) {
        if (songName == null || songName.isBlank()) {
            return "请告诉我你想听什么歌。";
        }

        try {
            String keyword = songName;
            if (artist != null && !artist.isBlank()) {
                keyword = artist + " " + songName;
            }

            String searchJson = httpGet(searchUrl(keyword));
            return parseSearchResult(searchJson);

        } catch (Exception e) {
            return "歌曲搜索失败：" + e.getMessage();
        }
    }

    public String getSongUrl(long songId) {
        try {
            String urlJson = httpGet(songUrl(songId));
            return parseSongUrl(urlJson);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseSearchResult(String json) {
        StringBuilder sb = new StringBuilder("🎵 搜索结果：\n");

        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            var result = root.getAsJsonObject("result");
            if (result == null) return "未找到歌曲。";
            var songs = result.getAsJsonArray("songs");
            if (songs == null || songs.size() == 0) return "未找到歌曲。";

            var song = songs.get(0).getAsJsonObject();
            String name = song.get("name").getAsString();
            long id = song.get("id").getAsLong();

            // 处理歌手字段（可能是数组或单个对象）
            String artistName = "未知";
            try { artistName = song.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString(); }
            catch (Exception e) { try { artistName = song.get("artist").getAsString(); } catch (Exception ignored) {} }

            int duration = 0;
            try { duration = song.get("duration").getAsInt() / 1000; } catch (Exception ignored) {}
            String durationStr = duration > 0 ? String.format("%d:%02d", duration / 60, duration % 60) : "未知时长";

            sb.append("▶ ").append(name).append(" — ").append(artistName)
                    .append(" (").append(durationStr).append(")\n");
            sb.append("歌曲ID: ").append(id).append("\n");

            String url = getSongUrl(id);
            if (url != null && !url.equals("null") && !url.isBlank()) {
                sb.append("音频URL: ").append(url);
                sb.append("\n🎵 已发送试听片段（约30秒），你可以在微信中收听。");
            } else {
                sb.append("\n⚠ 该歌曲暂时无法获取播放地址。");
            }

            return sb.toString();
        } catch (Exception e) {
            return "解析失败: " + e.getMessage();
        }
    }

    private String parseSongUrl(String json) {
        try {
            var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            var data = root.getAsJsonArray("data");
            if (data != null && data.size() > 0) {
                var item = data.get(0).getAsJsonObject();
                if (item.has("url") && !item.get("url").isJsonNull()) {
                    return item.get("url").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    public byte[] downloadSong(String audioUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(audioUrl))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return resp.body();
    }
}
