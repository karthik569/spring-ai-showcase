package com.example.springai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param question required — Spring AI rejects a null query deeper in the stack, which would surface as a 500
 * @param filename optional metadata filter, restricting retrieval to one ingested document
 */
public record RagQueryRequest(
        @NotBlank(message = "question must not be blank") String question,
        String filename
) {}
