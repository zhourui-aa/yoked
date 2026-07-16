package com.weather.exception;

/**
 * 天气查询自定义异常
 * 区别于系统异常,这类异常的错误信息可以直接展示给用户
 */
public class WeatherException extends RuntimeException {

    public WeatherException(String message) {
        super(message);
    }

    public WeatherException(String message, Throwable cause) {
        super(message, cause);
    }
}