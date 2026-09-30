package com.example.springai.health;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Answers whether the model behind {@code /api/ai/**} actually responds.
 * <p>
 * The default {@code ping} indicator only proves Tomcat is awake, which is exactly the misleading signal when
 * Ollama is running but the model failed to load. The probe is cached for {@code cache-ttl} because a health
 * scrape that fires a real completion at every request would cost more than the rest of the demo; the first
 * caller after the TTL pays the model's latency, and a wedged backend can make that one request slow.
 */
@Component("llm")
public class LlmHealthIndicator implements HealthIndicator {

    private final ChatModel chatModel;
    private final Duration cacheTtl;

    private volatile Health cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public LlmHealthIndicator(ChatModel chatModel,
                              @Value("${app.health.llm.cache-ttl:30s}") Duration cacheTtl) {
        this.chatModel = chatModel;
        this.cacheTtl = cacheTtl;
    }

    @Override
    public Health health() {
        Instant now = Instant.now();
        Health previous = cached;
        if (previous != null && Duration.between(cachedAt, now).compareTo(cacheTtl) < 0) {
            return previous;
        }

        Health probed = probe(now);
        cached = probed;
        cachedAt = now;
        return probed;
    }

    private Health probe(Instant startedAt) {
        long began = System.nanoTime();
        try {
            ChatResponse response = chatModel.call(new Prompt("Reply with the single word: ok"));
            String text = response.getResult().getOutput().getText();
            return Health.up()
                    .withDetail("model", response.getMetadata().getModel())
                    .withDetail("reply", text == null ? "" : text.trim())
                    .withDetail("latencyMs", Duration.ofNanos(System.nanoTime() - began).toMillis())
                    .withDetail("probedAt", startedAt.toString())
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getClass().getSimpleName())
                    .withDetail("cause", rootMessage(ex))
                    .withDetail("latencyMs", Duration.ofNanos(System.nanoTime() - began).toMillis())
                    .build();
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null) {
            return cause.getClass().getSimpleName();
        }
        String collapsed = message.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "...";
    }
}
