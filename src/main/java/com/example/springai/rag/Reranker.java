package com.example.springai.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Reorders retrieved chunks by a signal the vector store does not have.
 *
 * <p>An interface rather than a concrete class so the ranking can be swapped or stubbed: the retrieval
 * tests run offline with a deterministic implementation, and the shipped one is a single listwise model
 * call.
 */
@FunctionalInterface
public interface Reranker {

    /** @return the same documents reordered by relevance to {@code query}; never fewer than it was given */
    List<Document> rerank(String query, List<Document> documents);
}
