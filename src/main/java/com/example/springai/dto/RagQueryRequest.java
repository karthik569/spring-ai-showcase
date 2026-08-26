package com.example.springai.dto;

import java.util.List;

public record RagQueryRequest(
        String question,
        int topK
) {}
