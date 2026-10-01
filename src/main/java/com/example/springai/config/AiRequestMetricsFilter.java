package com.example.springai.config;

import com.example.springai.observability.AiMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Feeds {@link AiMetrics} from the servlet layer, so every {@code /api/ai} endpoint is measured without each
 * controller remembering to call in.
 *
 * <p>It sits <em>inside</em> {@link AiConcurrencyLimitFilter}: a request refused there with a 503 never
 * reaches this filter, so a rejection is not counted as a served call. That saturation is already visible on
 * the {@code app.ai.inflight} gauge.
 *
 * <p>A streaming response is measured only down to the point the request goes async — the first token rather
 * than the last. That is the number a caller feels (time to first byte), and the alternative would mean
 * holding the filter open for the whole generation.
 */
@Component
public class AiRequestMetricsFilter extends OncePerRequestFilter implements Ordered {

    private static final String AI_PATH_PREFIX = "/api/ai/";

    private final AiMetrics aiMetrics;

    public AiRequestMetricsFilter(AiMetrics aiMetrics) {
        this.aiMetrics = aiMetrics;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 300;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(AI_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            aiMetrics.recordRequest(request.getRequestURI(), millis, response.getStatus() >= 400);
        }
    }
}
