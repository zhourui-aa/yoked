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
