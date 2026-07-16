package weather;

public class WeatherInfo {
    private String city;
    private String temperature;
    private String feelsLike;
    private String condition;
    private String windDirection;
    private String windScale;
    private String humidity;
    private String pressure;
    private String visibility;
    private String precipitation;
    private String updateTime;

    // Getters and Setters
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getTemperature() { return temperature; }
    public void setTemperature(String temperature) { this.temperature = temperature; }

    public String getFeelsLike() { return feelsLike; }
    public void setFeelsLike(String feelsLike) { this.feelsLike = feelsLike; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public String getWindDirection() { return windDirection; }
    public void setWindDirection(String windDirection) { this.windDirection = windDirection; }

    public String getWindScale() { return windScale; }
    public void setWindScale(String windScale) { this.windScale = windScale; }

    public String getHumidity() { return humidity; }
    public void setHumidity(String humidity) { this.humidity = humidity; }

    public String getPressure() { return pressure; }
    public void setPressure(String pressure) { this.pressure = pressure; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public String getPrecipitation() { return precipitation; }
    public void setPrecipitation(String precipitation) { this.precipitation = precipitation; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }

    public String format() {
        return "🌤️ " + city + " 实时天气\n" +
                "━━━━━━━━━━━━━━━\n" +
                "🌡️ 温度：" + temperature + "\n" +
                "🤒 体感：" + feelsLike + "\n" +
                "☁️ 天气：" + condition + "\n" +
                "💨 风向：" + windDirection + "\n" +
                "🌪️ 风力：" + windScale + "\n" +
                "💧 湿度：" + humidity + "\n" +
                "📊 气压：" + pressure + "\n" +
                "👁️ 能见度：" + visibility + "\n" +
                "🌧️ 降水量：" + precipitation + "\n" +
                "━━━━━━━━━━━━━━━\n" +
                "🕐 更新时间：" + updateTime;
    }
}