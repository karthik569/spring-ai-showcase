package com.example.springai.dto;

public record WeatherInfo(
        String city,
        double temperatureCelsius,
        String condition,
        int humidityPercent,
        double windSpeedKmh
) {}
