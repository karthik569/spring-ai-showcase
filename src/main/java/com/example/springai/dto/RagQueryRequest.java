package com.example.springai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * @param question required — Spring AI rejects a null query deeper in the stack, which would surface as a 500
 * @param topK     0 means "use app.rag.top-k"
 * @param filename optional metadata filter, restricting retrieval to one ingested document
 */
public record RagQueryRequest(
        @NotBlank(message = "question must not be blank") String question,
        @Min(value = 0, message = "topK must not be negative") @Max(value = 20, message = "topK must be 20 or less") int topK,
        String filename
) {}
