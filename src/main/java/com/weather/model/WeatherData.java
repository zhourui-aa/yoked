package com.weather.model;

/**
 * 表示一个城市的天气数据。
 */
public class WeatherData {
    private final String city;
    private final String country;
    private final double temperatureC;
    private final double feelsLikeC;
    private final int humidity;
    private final String description;
    private final double windKph;
    private final String windDirection;

    public WeatherData(String city, String country, double temperatureC, double feelsLikeC,
                       int humidity, String description, double windKph, String windDirection) {
        this.city = city;
        this.country = country;
        this.temperatureC = temperatureC;
        this.feelsLikeC = feelsLikeC;
        this.humidity = humidity;
        this.description = description;
        this.windKph = windKph;
        this.windDirection = windDirection;
    }

    public String getCity() { return city; }
    public String getCountry() { return country; }
    public double getTemperatureC() { return temperatureC; }
    public double getFeelsLikeC() { return feelsLikeC; }
    public int getHumidity() { return humidity; }
    public String getDescription() { return description; }
    public double getWindKph() { return windKph; }
    public String getWindDirection() { return windDirection; }

    /**
     * 生成微信友好的天气展示格式（无框线、emoji 驱动、适合手机阅读）。
     *
     * <p>和 {@link #toString()} 的区别：
     * <ul>
     *   <li>不使用框线字符（微信比例字体会错位）</li>
     *   <li>不用空格对齐（同样因为比例字体）</li>
     *   <li>用 emoji 作为视觉锚点，每行一个指标</li>
     * </ul>
     */
    public String toWeChatFormat() {
        String weatherEmoji = weatherEmoji(description);

        StringBuilder sb = new StringBuilder();
        sb.append(weatherEmoji).append("  ").append(city).append(" 天气\n");
        sb.append("\n");
        sb.append("🌡 温度：").append(fmt(temperatureC)).append("°C\n");
        sb.append("🤔 体感：").append(fmt(feelsLikeC)).append("°C\n");
        sb.append("☁️ 天气：").append(description).append("\n");
        sb.append("💧 湿度：").append(humidity).append("%\n");

        // 风速：如果数据无效则隐藏整行
        if (windKph > 0 && !"未知".equals(windDirection)) {
            sb.append("🌬 风速：").append(windDirection)
              .append(" ").append(fmt(windKph)).append(" km/h");
        }

        return sb.toString();
    }

    /** 温度保留一位小数（整数也显示 .0，保持统一） */
    private static String fmt(double d) {
        return String.format("%.1f", d);
    }

    /** 天气描述 → 对应 emoji */
    private static String weatherEmoji(String text) {
        if (text == null) return "🌤";
        if (text.contains("雷")) return "⛈";
        if (text.contains("雨") || text.contains("阵")) return "🌧";
        if (text.contains("雪")) return "🌨";
        if (text.contains("雾")) return "🌫";
        if (text.contains("霾") || text.contains("沙")) return "🌪";
        if (text.contains("阴")) return "☁️";
        if (text.contains("多云")) return "⛅";
        if (text.contains("晴")) return "☀️";
        if (text.contains("风")) return "💨";
        return "🌤"; // 默认
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n┌──────────────────────────────────────────┐\n");
        sb.append(String.format("│  🌍  城市      : %s, %s%n", city, country));
        sb.append("├──────────────────────────────────────────┤\n");
        sb.append(String.format("│  🌡   温度      : %.1f°C  （体感 %.1f°C）%n", temperatureC, feelsLikeC));
        sb.append(String.format("│  ☁   天气      : %s%n", description));
        sb.append(String.format("│  💧  湿度      : %d%%%n", humidity));
        sb.append(String.format("│  💨  风速      : %s  %.1f km/h%n", windDirection, windKph));
        sb.append("└──────────────────────────────────────────┘\n");
        return sb.toString();
    }
}
