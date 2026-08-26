package com.example.springai.service;

import com.example.springai.dto.WeatherInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class WeatherToolServiceTest {

    private WeatherToolService weatherToolService;
    private Function<WeatherToolService.Request, WeatherInfo> weatherFunction;

    @BeforeEach
    void setUp() {
        weatherToolService = new WeatherToolService();
        weatherFunction = weatherToolService.getCurrentWeather();
    }

    @Test
    void testGetWeatherForKnownCity() {
        WeatherInfo result = weatherFunction.apply(new WeatherToolService.Request("Tokyo"));
        assertNotNull(result);
        assertEquals("Tokyo", result.city());
        assertEquals(18.5, result.temperatureCelsius());
        assertEquals("Partly Cloudy", result.condition());
    }

    @Test
    void testGetWeatherCaseInsensitive() {
        WeatherInfo result = weatherFunction.apply(new WeatherToolService.Request("  SAN FRANCISCO  "));
        assertNotNull(result);
        assertEquals("San Francisco", result.city());
        assertEquals(15.0, result.temperatureCelsius());
    }

    @Test
    void testGetWeatherForUnknownCityFallback() {
        WeatherInfo result = weatherFunction.apply(new WeatherToolService.Request("Sydney"));
        assertNotNull(result);
        assertEquals("Sydney", result.city());
        assertTrue(result.temperatureCelsius() > 0);
    }
}
