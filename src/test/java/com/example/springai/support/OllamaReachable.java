package com.example.springai.support;

import org.junit.jupiter.api.Assumptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * Tier-2 precondition, expressed through the store instead of a port probe: the measurement is meaningless
 * unless the live embedder answered and left something indexed behind.
 *
 * <p>Probing the store rather than {@code localhost:11434} means this works whatever host, port or
 * {@code OPENAI_BASE_URL} the backend sits behind, and it catches the second failure mode too — Ollama
 * running but the embedding model missing, which leaves the knowledge base empty.
 */
public final class OllamaReachable {

    private OllamaReachable() {
    }

    public static void orSkip(VectorStore vectorStore) {
        List<Document> probe;
        try {
            probe = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("policy")
                    .topK(1)
                    .similarityThresholdAll()
                    .build());
        } catch (Exception ex) {
            Assumptions.abort("the embedding backend did not answer ("
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ") — tier 2 skipped");
            return;
        }
        Assumptions.assumeTrue(!probe.isEmpty(),
                "the knowledge base holds nothing — start Ollama with all-minilm so boot can index it");
    }
}
