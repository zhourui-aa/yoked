package weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class WeatherTool implements Tool {
    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public String name() {
        return "weather_query";
    }

    @Override
    public String description() {
        return "查询指定中国城市的实时天气信息，包括温度、体感温度、天气状况、风向风力、湿度、气压、能见度、降水量等。当用户询问天气、穿衣建议、出行建议、是否需要带伞时调用。";
    }

    @Override
    public JsonObject parametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject city = new JsonObject();
        city.addProperty("type", "string");
        city.addProperty("description", "中国城市名称，如：北京、上海、广州、深圳、杭州、成都");
        properties.add("city", city);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("city");
        schema.add("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String city = args.has("city") ? args.get("city").getAsString() : null;
        if (city == null || city.trim().isEmpty()) {
            return new ToolResult(false, "请提供城市名称");
        }

        try {
            WeatherInfo info = weatherService.getCurrentWeather(city.trim());
            StringBuilder sb = new StringBuilder();
            sb.append("城市：").append(info.getCity()).append("\n");
            sb.append("温度：").append(info.getTemperature()).append("\n");
            sb.append("体感温度：").append(info.getFeelsLike()).append("\n");
            sb.append("天气状况：").append(info.getCondition()).append("\n");
            sb.append("风向：").append(info.getWindDirection()).append("\n");
            sb.append("风力：").append(info.getWindScale()).append("\n");
            sb.append("湿度：").append(info.getHumidity()).append("\n");
            sb.append("气压：").append(info.getPressure()).append("\n");
            sb.append("能见度：").append(info.getVisibility()).append("\n");
            sb.append("降水量：").append(info.getPrecipitation()).append("\n");
            sb.append("更新时间：").append(info.getUpdateTime());
            return new ToolResult(true, sb.toString());
        } catch (WeatherService.WeatherException e) {
            return new ToolResult(false, "查询失败：" + e.getMessage());
        } catch (Exception e) {
            return new ToolResult(false, "系统错误：" + e.getMessage());
        }
    }
}