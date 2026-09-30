package com.example.springai.dto;

/**
 * A chunk that was actually placed in the model's context, with the cosine similarity that selected it.
 *
 * @param score null when the store has no score for the chunk
 */
public record RetrievedChunk(
        String filename,
        Double score,
        String excerpt
) {}
