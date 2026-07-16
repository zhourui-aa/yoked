package com.weather.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对应 API 返回 JSON 中 "now" 字段的实时天气数据
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NowWeather {

    /** 温度，单位：摄氏度 */
    @JsonProperty("temp")
    private String temp;

    /** 体感温度，单位：摄氏度 */
    @JsonProperty("feelsLike")
    private String feelsLike;

    /** 天气状况描述：晴、多云、阴、雨等 */
    @JsonProperty("text")
    private String text;

    /** 风向 */
    @JsonProperty("windDir")
    private String windDir;

    /** 风力等级 */
    @JsonProperty("windScale")
    private String windScale;

    /** 相对湿度，百分比数值 */
    @JsonProperty("humidity")
    private String humidity;

    /** 大气压强，单位：百帕 */
    @JsonProperty("pressure")
    private String pressure;

    // ========== Getter ==========

    public String getTemp()           { return temp; }
    public String getFeelsLike()      { return feelsLike; }
    public String getText()           { return text; }
    public String getWindDir()        { return windDir; }
    public String getWindScale()      { return windScale; }
    public String getHumidity()       { return humidity; }
    public String getPressure()       { return pressure; }

    // ========== Setter ==========

    public void setTemp(String temp)                { this.temp = temp; }
    public void setFeelsLike(String feelsLike)      { this.feelsLike = feelsLike; }
    public void setText(String text)                { this.text = text; }
    public void setWindDir(String windDir)          { this.windDir = windDir; }
    public void setWindScale(String windScale)      { this.windScale = windScale; }
    public void setHumidity(String humidity)        { this.humidity = humidity; }
    public void setPressure(String pressure)        { this.pressure = pressure; }

    @Override
    public String toString() {
        return String.format(
                "天气: %s | 温度: %s°C (体感 %s°C) | %s %s级 | 湿度: %s%% | 气压: %shPa",
                text, temp, feelsLike, windDir, windScale, humidity, pressure
        );
    }
}