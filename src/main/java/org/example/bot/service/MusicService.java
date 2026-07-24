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

    /** 服务是否可用 */
    boolean isAvailable();
}
