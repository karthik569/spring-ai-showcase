package com.example.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Tool callback giving the model a trustworthy clock: small models answer "what time is it" from training
 * data otherwise. An unknown timezone is reported in the result instead of throwing, so the model can
 * recover by retrying with an IANA name.
 */
@Configuration
public class DateTimeToolService {

    private static final Logger log = LoggerFactory.getLogger(DateTimeToolService.class);

    /**
     * @param timezone IANA zone such as {@code Europe/London}; blank means the server default zone
     */
    public record Request(String timezone) {}

    @Bean
    @Description("Get the current date and time, optionally in an IANA timezone like Europe/London or Asia/Kolkata")
    public Function<Request, Map<String, String>> getCurrentDateTime() {
        return request -> {
            log.info("[SPRING-AI-TOOL] Executing getCurrentDateTime tool call for timezone={}", request.timezone());
            Map<String, String> result = new LinkedHashMap<>();
            ZoneId zone;
            if (request.timezone() == null || request.timezone().isBlank()) {
                zone = ZoneId.systemDefault();
            } else {
                try {
                    zone = ZoneId.of(request.timezone().trim());
                } catch (RuntimeException ex) {
                    result.put("error", "unknown timezone: " + request.timezone()
                            + " — use an IANA name like Europe/London");
                    return result;
                }
            }
            ZonedDateTime now = ZonedDateTime.now(zone);
            result.put("timezone", zone.getId());
            result.put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            result.put("readable", now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy HH:mm:ss z")));
            return result;
        };
    }
}
