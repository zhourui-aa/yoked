package org.example.bot.service;

/**
 * 音乐搜索服务接口 — 根据歌名和歌手搜索歌曲，获取播放地址。
 */
public interface MusicService {

    /**
     * 搜索歌曲。
     * @param songName 歌曲名称（必填）
     * @param artist   歌手名称（可选）
     * @return 搜索结果文本，包含歌曲信息和音频地址
     */
    String search(String songName, String artist);

    /**
     * 下载歌曲音频数据。
     * @param audioUrl 音频地址（由 search 结果解析出的 URL）
     * @return 音频字节数据
     */
    byte[] downloadSong(String audioUrl) throws Exception;

    /** 服务是否可用 */
    boolean isAvailable();
}
