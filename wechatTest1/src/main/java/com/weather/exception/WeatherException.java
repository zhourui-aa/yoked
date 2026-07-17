package com.weather.exception;

/**
 * Custom exception for weather-related errors (invalid city, network failure, parse error, etc.).
 * All user-facing messages are set at construction time so the caller can simply print getMessage().
 */
public class WeatherException extends Exception {

    public WeatherException(String message) {
        super(message);
    }

    public WeatherException(String message, Throwable cause) {
        super(message, cause);
    }
}
