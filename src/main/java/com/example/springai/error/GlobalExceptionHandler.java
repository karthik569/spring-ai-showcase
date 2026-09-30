package com.example.springai.error;

import com.example.springai.config.EndpointLoggingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves AI failures that Spring MVC would otherwise leave to Tomcat, which logs a full
 * container stack trace and answers with a body that says nothing about the model backend.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int DETAIL_CHARS = 300;
    private static final int RAW_OUTPUT_CHARS = 500;
    private static final int RETRY_AFTER_SECONDS = 5;

    @ExceptionHandler(StructuredOutputParseException.class)
    public ResponseEntity<Map<String, Object>> unparseableModelOutput(StructuredOutputParseException ex) {
        log.warn("Rejected model output: {}", ex.getMessage());
        Map<String, Object> body = problem(HttpStatus.UNPROCESSABLE_ENTITY, "model_output_unparseable", ex.getMessage());
        if (ex.getCause() != null) {
            body.put("conversionFailure", excerpt(rootMessage(ex.getCause()), DETAIL_CHARS));
        }
        body.put("rawModelOutput", excerpt(ex.getRawModelOutput(), RAW_OUTPUT_CHARS));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(ImageDownloadException.class)
    public ResponseEntity<Map<String, Object>> imageUnavailable(ImageDownloadException ex) {
        log.warn("Vision input rejected: {}", ex.getMessage());
        return problemResponse(HttpStatus.BAD_REQUEST, "image_unavailable", ex.getMessage());
    }

    // Without this, a blank question reaches Spring AI and comes back as IllegalArgumentException from
    // several frames below the controller, which Tomcat renders as a message-less 500 plus a stack dump.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidRequestBody(MethodArgumentNotValidException ex) {
        String violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Rejected request body: {}", violations);
        return problemResponse(HttpStatus.BAD_REQUEST, "invalid_request", violations);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> rejectedRequest(ResponseStatusException ex) {
        log.warn("Rejected request ({}): {}", ex.getStatusCode(), ex.getReason());
        return problemResponse(ex.getStatusCode(), "request_rejected", ex.getReason());
    }

    // The multipart cap fires before the controller sees the file, so the size limit is reported here.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> documentTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected: {}", ex.getMessage());
        return problemResponse(HttpStatus.PAYLOAD_TOO_LARGE, "document_too_large", ex.getMessage());
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> backendUnreachable(ResourceAccessException ex) {
        log.warn("LLM backend unreachable: {}", rootMessage(ex));
        return retryable(HttpStatus.SERVICE_UNAVAILABLE, "llm_backend_unreachable", rootMessage(ex));
    }

    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<Map<String, Object>> backendRetriesExhausted(TransientAiException ex) {
        log.warn("LLM backend kept failing after retries: {}", rootMessage(ex));
        return retryable(HttpStatus.SERVICE_UNAVAILABLE, "llm_backend_unavailable", rootMessage(ex));
    }

    // Tells the caller (and the demo page) that the failure is the backend's, not the request's, so retrying
    // unchanged is expected to work once the model process is back.
    private ResponseEntity<Map<String, Object>> retryable(HttpStatusCode status, String code, String detail) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                .body(problem(status, code, detail));
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, Object>> backendRejected(NonTransientAiException ex) {
        log.warn("LLM backend rejected the request: {}", rootMessage(ex));
        return problemResponse(HttpStatus.BAD_GATEWAY, "llm_backend_rejected", rootMessage(ex));
    }

    private ResponseEntity<Map<String, Object>> problemResponse(HttpStatusCode status, String code, String detail) {
        return ResponseEntity.status(status).body(problem(status, code, detail));
    }

    private Map<String, Object> problem(HttpStatusCode status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("status", status.value());
        body.put("detail", excerpt(detail, DETAIL_CHARS));
        body.put("requestId", MDC.get(EndpointLoggingFilter.REQUEST_ID_KEY));
        return body;
    }

    private String excerpt(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= maxChars ? collapsed : collapsed.substring(0, maxChars) + "...";
    }

    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
