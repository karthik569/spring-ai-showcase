package com.example.springai.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process roll-up of AI usage, for the dashboard endpoint.
 *
 * <p>The Micrometer instruments ({@code ai.requests.total}, {@code ai.tokens.total}, {@code ai.latency},
 * {@code ai.cache.requests.total}) are the durable record and are what {@code /actuator/metrics} reports. The
 * counters kept here exist because a Micrometer counter cannot be enumerated back into a per-endpoint table
 * without already knowing every tag value — and that table is exactly what {@link #summary()} renders.
 *
 * <p>Percentiles come from a bounded ring of the last {@value #LATENCY_WINDOW} request times. With a single
 * local model the shape of the distribution is the interesting part, and a real histogram would be more
 * machinery than this showcase needs.
 */
@Component
public class AiMetrics {

    private static final int LATENCY_WINDOW = 512;

    private final MeterRegistry registry;

    private final LongAdder requests = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();

    private final Map<String, LongAdder> requestsByEndpoint = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> errorsByEndpoint = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> recentLatencyMs = new ArrayDeque<>();

    private final long startedAtNanos = System.nanoTime();

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One served request: counts it, records its wall time, and folds in the error tally when it failed. */
    public void recordRequest(String endpoint, long millis, boolean failed) {
        requests.increment();
        requestsByEndpoint.computeIfAbsent(endpoint, key -> new LongAdder()).increment();
        if (failed) {
            errors.increment();
            errorsByEndpoint.computeIfAbsent(endpoint, key -> new LongAdder()).increment();
        }
        registry.counter("ai.requests.total", "endpoint", endpoint, "outcome", failed ? "error" : "ok").increment();
        registry.timer("ai.latency", "endpoint", endpoint).record(millis, TimeUnit.MILLISECONDS);
        rememberLatency(millis);
    }

    /**
     * Records a completion's token counts. A zero is dropped rather than counted for the same reason
     * {@code TokenUsageAdvisor} skips such a line: a streamed call reaches the advisor with counters of zero,
     * and counting them would drag every average toward it.
     */
    public void recordTokens(Integer prompt, Integer completion) {
        if (prompt != null && prompt > 0) {
            promptTokens.add(prompt);
            registry.counter("ai.tokens.total", "type", "prompt").increment(prompt);
        }
        if (completion != null && completion > 0) {
            completionTokens.add(completion);
            registry.counter("ai.tokens.total", "type", "completion").increment(completion);
        }
    }

    /** A semantic-cache lookup outcome, reported separately so the hit rate is not inferred from token deltas. */
    public void recordCache(boolean hit) {
        (hit ? cacheHits : cacheMisses).increment();
        registry.counter("ai.cache.requests.total", "result", hit ? "hit" : "miss").increment();
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("uptimeSeconds", (System.nanoTime() - startedAtNanos) / 1_000_000_000L);

        Map<String, Object> requestBlock = new LinkedHashMap<>();
        requestBlock.put("total", requests.sum());
        requestBlock.put("errors", errors.sum());
        requestBlock.put("byEndpoint", counts(requestsByEndpoint));
        requestBlock.put("errorsByEndpoint", counts(errorsByEndpoint));
        summary.put("requests", requestBlock);

        long prompt = promptTokens.sum();
        long completion = completionTokens.sum();
        Map<String, Object> tokenBlock = new LinkedHashMap<>();
        tokenBlock.put("prompt", prompt);
        tokenBlock.put("completion", completion);
        tokenBlock.put("total", prompt + completion);
        summary.put("tokens", tokenBlock);

        long hits = cacheHits.sum();
        long misses = cacheMisses.sum();
        Map<String, Object> cacheBlock = new LinkedHashMap<>();
        cacheBlock.put("hits", hits);
        cacheBlock.put("misses", misses);
        cacheBlock.put("hitRate", hits + misses == 0 ? null : round((double) hits / (hits + misses)));
        summary.put("cache", cacheBlock);

        summary.put("latencyMs", latencyBlock());
        return summary;
    }

    private Map<String, Object> latencyBlock() {
        List<Long> sorted = snapshotLatencies();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("samples", sorted.size());
        if (sorted.isEmpty()) {
            block.put("p50", null);
            block.put("p95", null);
            block.put("max", null);
            block.put("average", null);
            return block;
        }
        double average = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        block.put("p50", percentile(sorted, 0.50));
        block.put("p95", percentile(sorted, 0.95));
        block.put("max", sorted.get(sorted.size() - 1));
        block.put("average", round(average));
        return block;
    }

    private void rememberLatency(long millis) {
        synchronized (recentLatencyMs) {
            recentLatencyMs.addLast(millis);
            while (recentLatencyMs.size() > LATENCY_WINDOW) {
                recentLatencyMs.removeFirst();
            }
        }
    }

    private List<Long> snapshotLatencies() {
        synchronized (recentLatencyMs) {
            List<Long> copy = new ArrayList<>(recentLatencyMs);
            copy.sort(Long::compareTo);
            return copy;
        }
    }

    /** Nearest-rank percentile over an ascending list; small samples are exact, larger ones approximate. */
    private static long percentile(List<Long> ascending, double fraction) {
        int index = (int) Math.ceil(fraction * ascending.size()) - 1;
        return ascending.get(Math.max(0, Math.min(index, ascending.size() - 1)));
    }

    private static Map<String, Long> counts(Map<String, LongAdder> source) {
        Map<String, Long> sorted = new java.util.TreeMap<>();
        source.forEach((key, adder) -> sorted.put(key, adder.sum()));
        return sorted;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
