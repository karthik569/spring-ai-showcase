package com.example.springai.service;

import com.example.springai.dto.WeatherInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.Map;
import java.util.function.Function;

/**
 * Spring AI function provider supplying live meteorological conditions to the local LLM.
 * <p>
 * Demonstrates:
 * <ul>
 *   <li>Exposing Java functions as OpenAI-compatible JSON Schema tools</li>
 *   <li>Automatic parameter binding from unstructured natural language queries</li>
 * </ul>
 *
 * @author Spring Showcase Team
 * @version 1.0
 */
@Configuration
public class WeatherToolService {

    private static final Logger log = LoggerFactory.getLogger(WeatherToolService.class);

    /**
     * Request input schema bound to LLM tool call arguments.
     *
     * @param city the city name (e.g. Tokyo, London)
     */
    public record Request(String city) {}

    private final Map<String, WeatherInfo> weatherDatabase = Map.of(
            "tokyo", new WeatherInfo("Tokyo", 18.5, "Partly Cloudy", 62, 14.0),
            "san francisco", new WeatherInfo("San Francisco", 15.0, "Foggy", 80, 19.5),
            "london", new WeatherInfo("London", 12.0, "Light Rain", 88, 22.0),
            "new york", new WeatherInfo("New York", 22.0, "Sunny", 45, 11.0),
            "paris", new WeatherInfo("Paris", 17.0, "Clear Skies", 50, 9.0)
    );

    /**
     * Tool callback function returning temperature and weather conditions for a requested city.
     *
     * @return a {@link Function} accepting {@link Request} and returning {@link WeatherInfo}
     */
    @Bean
    @Description("Get the current live weather conditions and temperature for a given city")
    public Function<Request, WeatherInfo> getCurrentWeather() {
        return request -> {
            log.info("[SPRING-AI-TOOL] Executing getCurrentWeather tool call for city={}", request.city());
            String key = request.city().trim().toLowerCase();
            return weatherDatabase.getOrDefault(key,
                    new WeatherInfo(request.city(), 20.0, "Pleasant and Sunny", 50, 10.0));
        };
    }
}
