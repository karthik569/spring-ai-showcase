package com.example.springai.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Decides which retrieved chunks could actually answer a question.
 *
 * <p>Separate from {@link Reranker} because the question is different: a re-ranker orders everything, a
 * grader is allowed to say none of it is usable. That "none" is what a self-correcting RAG refuses on.
 */
@FunctionalInterface
public interface ContextGrader {

    /**
     * @return the subset of {@code candidates} judged able to answer {@code question}; empty means none can
     */
    List<Document> relevant(String question, List<Document> candidates);
}
