package com.example.springai.service;

import com.example.springai.rag.StubEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The knowledge-base tool against the real store and splitter with a stub embedder, mirroring
 * {@code GoldenQuestionsTest}: it must return ranked, filename-tagged hits so the model has something
 * citable to work from.
 */
class KnowledgeBaseToolServiceTest {

    private KnowledgeBaseToolService service;

    @BeforeEach
    void indexThePolicy() throws IOException {
        SimpleVectorStore store = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        RagDocumentIngestionService ingestion = new RagDocumentIngestionService(store,
                new RagStorePersistence(store, false, "target/rag-eval-disabled", false));
        ingestion.ingest(new ClassPathResource("docs/company-policy.md"), "company-policy.md", "classpath");
        service = new KnowledgeBaseToolService(store, 4);
    }

    @Test
    void returnsRankedHitsTaggedWithTheirSourceFile() {
        List<KnowledgeBaseToolService.Hit> hits = service.searchKnowledgeBase()
                .apply(new KnowledgeBaseToolService.Request("annual leave allowance"));

        assertFalse(hits.isEmpty(), "a relevant query must return at least one chunk");
        assertEquals("company-policy.md", hits.get(0).filename());
        assertFalse(hits.get(0).excerpt().isBlank());
    }
}
