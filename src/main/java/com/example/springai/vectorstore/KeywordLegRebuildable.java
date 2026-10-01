package com.example.springai.vectorstore;

/**
 * A store that keeps a BM25 keyword index in step with its own writes, and can rebuild it.
 *
 * <p>Exists so the boot-time restore path can repopulate the keyword leg without knowing which decorator
 * it is holding: {@code RagDocumentIngestionService} used to test {@code instanceof HybridVectorStore},
 * which silently stopped matching once a re-ranking decorator became the primary {@code VectorStore}.
 */
public interface KeywordLegRebuildable {

    void rebuildKeywordIndex();
}
