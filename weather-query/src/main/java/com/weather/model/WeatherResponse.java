package com.weather.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对应和风天气实时天气 API 的最外层响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeatherResponse {

    /** API 状态码，"200" 表示成功 */
    @JsonProperty("code")
    private String code;

    /** 实时天气数据 */
    @JsonProperty("now")
    private NowWeather now;

    /** 城市名称（从城市搜索阶段获取，不是 API 原样返回） */
    private String cityName;

    // ========== Getter ==========

    public String getCode()          { return code; }
    public NowWeather getNow()       { return now; }
    public String getCityName()      { return cityName; }

    // ========== Setter ==========

    public void setCode(String code)          { this.code = code; }
    public void setNow(NowWeather now)         { this.now = now; }
    public void setCityName(String cityName)   { this.cityName = cityName; }

    /**
     * 判断 API 是否成功返回
     */
    public boolean isSuccess() {
        return "200".equals(code);
    }
}
