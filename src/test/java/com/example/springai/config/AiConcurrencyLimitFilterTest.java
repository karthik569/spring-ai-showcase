package com.example.springai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConcurrencyLimitFilterTest {

    @Test
    void refusesASecondCallWhileTheFirstHoldsTheOnlyPermit() throws Exception {
        AiConcurrencyLimitFilter filter = new AiConcurrencyLimitFilter(1, new ObjectMapper(), new SimpleMeterRegistry());
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> runQuietly(filter, (request, response) -> {
            inside.countDown();
            awaitQuietly(release);
        }));
        holder.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS), "first call never reached the chain");

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        AtomicBoolean secondReachedChain = new AtomicBoolean(false);
        filter.doFilter(aiRequest(), rejected, countingChain(secondReachedChain));

        assertEquals(503, rejected.getStatus());
        assertNotNull(rejected.getHeader("Retry-After"), "a 503 without Retry-After tells the caller to guess");
        assertTrue(rejected.getContentAsString().contains("too_many_concurrent_requests"));
        assertFalse(secondReachedChain.get(), "the rejected call must not reach the model");

        release.countDown();
        holder.join(5_000);

        AtomicBoolean thirdReachedChain = new AtomicBoolean(false);
        filter.doFilter(aiRequest(), new MockHttpServletResponse(), countingChain(thirdReachedChain));
        assertTrue(thirdReachedChain.get(), "the permit was never returned after the first call finished");
    }

    @Test
    void leavesNonAiPathsUnmetered() throws Exception {
        AiConcurrencyLimitFilter filter = new AiConcurrencyLimitFilter(1, new ObjectMapper(), new SimpleMeterRegistry());
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
                countingChain(reached));

        assertTrue(reached.get());
    }

    private MockHttpServletRequest aiRequest() {
        return new MockHttpServletRequest("GET", "/api/ai/chat");
    }

    private jakarta.servlet.FilterChain countingChain(AtomicBoolean reached) {
        return (request, response) -> reached.set(true);
    }

    private void runQuietly(AiConcurrencyLimitFilter filter, jakarta.servlet.FilterChain chain) {
        try {
            filter.doFilter(aiRequest(), new MockHttpServletResponse(), chain);
        } catch (ServletException | IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
