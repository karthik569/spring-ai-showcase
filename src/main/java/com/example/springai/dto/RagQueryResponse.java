package com.example.springai.dto;

import java.util.List;

public record RagQueryResponse(
        String question,
        String answer,
        List<RetrievedChunk> sourceDocuments,
        long responseTimeMs
) {}
