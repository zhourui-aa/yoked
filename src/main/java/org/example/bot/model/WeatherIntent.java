package org.example.bot.model;

/**
 * 天气查询意图提取结果 — 由 AI 分析用户消息后返回。
 *
 * @param isWeather 用户是否在询问天气
 * @param city      AI 提取出的城市名（isWeather=false 或未识别到城市时为 null）
 */
public class WeatherIntent {
    private final boolean isWeather;
    private final String city;

    public WeatherIntent(boolean isWeather, String city) {
        this.isWeather = isWeather;
        this.city = city;
    }

    /** 快速创建一个"非天气"结果 */
    public static WeatherIntent notWeather() {
        return new WeatherIntent(false, null);
    }

    /** 快速创建一个天气查询结果 */
    public static WeatherIntent weather(String city) {
        return new WeatherIntent(true, city);
    }

    public boolean isWeather() { return isWeather; }

    /** 城市名，未识别到时返回 null */
    public String city() { return city; }

    /** 是否为天气查询但没有指定城市 */
    public boolean cityIsEmpty() {
        return city == null || city.isBlank();
    }
}
