package com.example.springai.controller;

import com.example.springai.observability.AiMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Renders {@link AiMetrics} as one JSON object, so the demo page can show what the process has served without
 * a metrics stack. The raw series are still on {@code /actuator/metrics}.
 */
@RestController
@RequestMapping("/api/ai/metrics")
@Tag(name = "Metrics", description = "In-process request, token, cache and latency counters")
public class MetricsController {

    private final AiMetrics aiMetrics;

    public MetricsController(AiMetrics aiMetrics) {
        this.aiMetrics = aiMetrics;
    }

    @GetMapping("/summary")
    @Operation(summary = "Metrics summary",
            description = "Requests, tokens, cache hit rate and latency percentiles for this process. The raw series are on /actuator/metrics.")
    public Map<String, Object> summary() {
        return aiMetrics.summary();
    }
}
