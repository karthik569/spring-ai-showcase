package com.example.springai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class EndpointLoggingFilter extends OncePerRequestFilter implements Ordered {

    public static final String REQUEST_ID_KEY = "requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("ENDPOINT_ACCESS");
    private static final int MAX_BODY_CHARS = 2000;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = headerOrGenerated(request);
        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startedAt = System.nanoTime();
        // Buffering a streamed response behind the caching wrapper would delay every token until the stream ends.
        boolean streaming = isStreaming(request);
        HttpServletRequest loggedRequest = streaming ? request : new ContentCachingRequestWrapper(request);
        HttpServletResponse loggedResponse = streaming ? response : new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(loggedRequest, loggedResponse);
            ACCESS_LOG.info(summary(loggedRequest, loggedResponse, startedAt) + describe(streaming, loggedRequest, loggedResponse));
        } catch (ServletException | IOException | RuntimeException ex) {
            ACCESS_LOG.warn(summary(loggedRequest, loggedResponse, startedAt)
                    + " FAILED " + ex.getClass().getSimpleName() + ": " + rootMessage(ex));
            throw ex;
        } finally {
            if (loggedResponse instanceof ContentCachingResponseWrapper cached) {
                cached.copyBodyToResponse();
            }
            MDC.remove(REQUEST_ID_KEY);
        }
    }

    private String describe(boolean streaming, HttpServletRequest request, HttpServletResponse response) {
        if (streaming) {
            return " body=streamed";
        }
        ContentCachingRequestWrapper cachedRequest = (ContentCachingRequestWrapper) request;
        ContentCachingResponseWrapper cachedResponse = (ContentCachingResponseWrapper) response;
        return " req=" + body(cachedRequest.getContentAsByteArray(), cachedRequest.getContentType(),
                    cachedRequest.getCharacterEncoding())
                + " resp=" + body(cachedResponse.getContentAsByteArray(), cachedResponse.getContentType(),
                    cachedResponse.getCharacterEncoding());
    }

    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? "<no message>" : message.replaceAll("\\s+", " ").trim();
    }

    private String headerOrGenerated(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return (incoming != null && !incoming.isBlank())
                ? incoming.substring(0, Math.min(incoming.length(), 32))
                : UUID.randomUUID().toString().substring(0, 8);
    }

    private boolean isStreaming(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return request.getRequestURI().endsWith("/stream")
                || (accept != null && accept.contains("text/event-stream"));
    }

    private String summary(HttpServletRequest request, HttpServletResponse response, long startedAt) {
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        String query = request.getQueryString();
        return request.getMethod() + " " + (query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query)
                + " -> " + response.getStatus() + " " + millis + "ms"
                + " ip=" + request.getRemoteAddr();
    }

    private String body(byte[] raw, String contentType, String encoding) {
        if (raw == null || raw.length == 0) {
            return "<empty>";
        }
        if (contentType != null && !isTextual(contentType)) {
            return "<binary " + raw.length + "B>";
        }
        String text = new String(raw, charset(encoding)).replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_BODY_CHARS
                ? text
                : text.substring(0, MAX_BODY_CHARS) + "...<truncated " + text.length() + "B>";
    }

    private boolean isTextual(String contentType) {
        String lower = contentType.toLowerCase();
        return !lower.startsWith("multipart/")
                && !lower.startsWith("image/")
                && (lower.contains("json") || lower.contains("text") || lower.contains("xml") || lower.contains("form"));
    }

    private Charset charset(String encoding) {
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception unsupported) {
            return StandardCharsets.UTF_8;
        }
    }
}
