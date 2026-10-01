package com.example.springai.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiMetricsTest {

    private final AiMetrics metrics = new AiMetrics(new SimpleMeterRegistry());

    @Test
    void summarizesRequestsErrorsAndTokens() {
        metrics.recordRequest("/api/ai/chat", 120, false);
        metrics.recordRequest("/api/ai/chat", 340, false);
        metrics.recordRequest("/api/ai/rag/query", 890, true);
        metrics.recordTokens(100, 20);
        metrics.recordTokens(50, 10);

        Map<String, Object> requests = block("requests");
        assertEquals(3L, requests.get("total"));
        assertEquals(1L, requests.get("errors"));

        Map<String, Object> tokens = block("tokens");
        assertEquals(150L, tokens.get("prompt"));
        assertEquals(30L, tokens.get("completion"));
        assertEquals(180L, tokens.get("total"));

        Map<String, Object> latency = block("latencyMs");
        assertEquals(3, latency.get("samples"));
        assertEquals(890L, latency.get("max"));
    }

    @Test
    void ignoresZeroTokenReportsAndComputesCacheHitRate() {
        metrics.recordTokens(0, 0);
        metrics.recordCache(true);
        metrics.recordCache(false);
        metrics.recordCache(true);

        assertEquals(0L, block("tokens").get("total"));

        Map<String, Object> cache = block("cache");
        assertEquals(2L, cache.get("hits"));
        assertEquals(1L, cache.get("misses"));
        assertEquals(0.667, cache.get("hitRate"));
    }

    @Test
    void reportsNullsBeforeAnyLookup() {
        assertNull(block("cache").get("hitRate"));
        assertEquals(0, block("latencyMs").get("samples"));
        assertNull(block("latencyMs").get("p95"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> block(String key) {
        return (Map<String, Object>) metrics.summary().get(key);
    }
}
