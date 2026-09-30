package com.example.springai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caps how many model calls run at once.
 * <p>
 * A single local model serialises these anyway, so the queue would form inside Ollama where nothing reports
 * it; rejecting early keeps one slow generation from exhausting the Tomcat thread pool and turns the backlog
 * into a 503 with a Retry-After instead of a wall of timeouts. Requests are not queued — a caller that wants
 * a slot asks again.
 */
@Component
public class AiConcurrencyLimitFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(AiConcurrencyLimitFilter.class);
    private static final int RETRY_AFTER_SECONDS = 2;
    private static final String AI_PATH_PREFIX = "/api/ai/";

    private final Semaphore permits;
    private final int maxConcurrent;
    private final ObjectMapper objectMapper;

    public AiConcurrencyLimitFilter(@Value("${app.ai.max-concurrent-requests:4}") int maxConcurrent,
                                    ObjectMapper objectMapper,
                                    MeterRegistry meterRegistry) {
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent, true);
        this.objectMapper = objectMapper;
        Gauge.builder("app.ai.inflight", permits, semaphore -> maxConcurrent - semaphore.availablePermits())
                .description("Model calls running right now")
                .register(meterRegistry);
    }

    @Override
    public int getOrder() {
        // After EndpointLoggingFilter, so a rejected request still reaches endpoints.log with its requestId.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(AI_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!permits.tryAcquire()) {
            reject(request, response);
            return;
        }

        // A streaming endpoint hands the response to another thread and returns here, so releasing in this
        // finally block would free the permit while the model is still generating.
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        };

        boolean asyncStarted = false;
        try {
            chain.doFilter(request, response);
            asyncStarted = request.isAsyncStarted();
            if (asyncStarted) {
                addReleaseListener(request.getAsyncContext(), release);
            }
        } finally {
            if (!asyncStarted) {
                release.run();
            }
        }
    }

    private void addReleaseListener(AsyncContext context, Runnable release) {
        try {
            context.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    release.run();
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    release.run();
                }

                @Override
                public void onError(AsyncEvent event) {
                    release.run();
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // The new AsyncContext keeps its own listener registered below.
                }
            });
        } catch (IllegalStateException alreadySettled) {
            // The stream completed between doFilter returning and the listener being attached.
            release.run();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.warn("Refused {} because {} model calls are already running", request.getRequestURI(), maxConcurrent);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "too_many_concurrent_requests");
        body.put("status", HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        body.put("detail", "This model backend is serving " + maxConcurrent + " calls already; ask again shortly.");
        body.put("requestId", MDC.get(EndpointLoggingFilter.REQUEST_ID_KEY));

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
