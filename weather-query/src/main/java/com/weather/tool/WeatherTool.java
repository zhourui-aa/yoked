package com.weather.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weather.exception.WeatherException;
import com.weather.model.WeatherResponse;
import com.weather.service.LocationService;
import com.weather.service.WeatherService;

public class WeatherTool {
    private final WeatherService weatherService;
    private final LocationService locationService;
    private final ObjectMapper objectMapper;

    public WeatherTool(WeatherService weatherService,
                       LocationService locationService,
                       ObjectMapper objectMapper) {
        this.weatherService = weatherService;
        this.locationService = locationService;
        this.objectMapper = objectMapper;
    }

    /**
     * AI 工具调用入口
     * @param toolArguments AI 传来的 JSON 参数，如 {"city":"北京"}
     * @return 格式化的天气字符串（直接可以发回给 AI）
     */
    public String execute(String toolArguments) {
        try {
            JsonNode args = objectMapper.readTree(toolArguments);
            String city = args.path("city").asText().trim();
            if (city.isEmpty()) {
                city = autoLocate();
            }
            return queryWeather(city);
        } catch (WeatherException e) {
            return "查询失败：" + e.getMessage();
        } catch (Exception e) {
            return "查询异常：" + e.getMessage();
        }
    }

    /**
     * 直接按城市名查询（未来其他工具也可复用）
     */
    public String queryWeather(String city) {
        try {
            WeatherResponse weather = weatherService.queryByCity(city);
            return formatWeather(weather);
        } catch (WeatherException e) {
            return "查询失败：" + e.getMessage();
        }
    }

    /**
     * 把 WeatherResponse 格式化成 AI 能读懂的字符串
     */
    private String formatWeather(WeatherResponse weather) {
        return String.format(
                "城市:%s 温度:%s°C 体感:%s°C 天气:%s 风向:%s %s级 湿度:%s%% 气压:%shPa",
                weather.getCityName(), weather.getNow().getTemp(),
                weather.getNow().getFeelsLike(), weather.getNow().getText(),
                weather.getNow().getWindDir(), weather.getNow().getWindScale(),
                weather.getNow().getHumidity(), weather.getNow().getPressure()
        );
    }

    /**
     * 自动定位当前城市
     */
    private String autoLocate() {
        System.out.println("用户未指定城市，自动定位中...");
        try {
            String city = locationService.getCurrentCity();
            System.out.println(">>> 自动定位城市: " + city);
            return city;
        } catch (Exception e) {
            System.err.println(">>> 自动定位失败: " + e.getMessage());
            return "北京";  // 定位失败兜底
        }
    }
}
