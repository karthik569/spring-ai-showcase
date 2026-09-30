package com.example.springai.rag;

import com.example.springai.rag.GoldenQuestions.GoldenQuestion;
import com.example.springai.rag.GoldenQuestions.Row;
import com.example.springai.service.RagDocumentIngestionService;
import com.example.springai.service.RagStorePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 of the retrieval eval: the shipped policy document plus a test-only runbook, indexed into the real
 * {@link SimpleVectorStore} through the real splitter, searched with the real filter and threshold code, and
 * embedded by {@link StubEmbeddingModel} so it runs with Ollama switched off.
 *
 * <p>Gates are on <em>rank</em>, never on score value: the dataset's {@code maxFirstHitRank} entries are the
 * ranks this stub actually produces — all thirteen match rows land on rank 1 today — so a failure means a
 * code change reordered retrieval, not that a model feels different this morning. Tier 2
 * ({@link LiveRetrievalComparisonTest}) measures the live embedder against the same dataset and reports
 * without asserting.
 */
class GoldenQuestionsTest {

    private SimpleVectorStore store;

    @BeforeEach
    void indexTheCorpus() throws IOException {
        store = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        // Persistence off: this harness measures retrieval, and writing a snapshot would couple the
        // measurement to the store's file format. Ingesting through the real service keeps the splitter, the
        // chunk metadata and the eval honest with each other.
        RagDocumentIngestionService ingestion = new RagDocumentIngestionService(store,
                new RagStorePersistence(store, false, "target/rag-eval-disabled", false));
        ingestion.ingest(new ClassPathResource("docs/" + GoldenQuestions.POLICY),
                GoldenQuestions.POLICY, "classpath");
        ingestion.ingest(new ClassPathResource("rag/corpus/" + GoldenQuestions.RUNBOOK),
                GoldenQuestions.RUNBOOK, "test-corpus");
    }

    @Test
    void goldenQuestionsRankTheirGoldChunkWithinBudget() throws IOException {
        List<GoldenQuestion> questions = GoldenQuestions.load();
        assertTrue(questions.size() >= 10, "the dataset shrank to " + questions.size() + " rows");

        List<Row> rows = GoldenQuestions.measure(store, questions);
        List<String> failures = GoldenQuestions.gate(rows);
        assertTrue(failures.isEmpty(), GoldenQuestions.table("stub embedder", rows, failures));
    }

    /**
     * The mechanism behind the guide's "RAG is silently not grounded" lesson, asserted without a model: a
     * floor just above the best score turns a non-empty result set into an empty one.
     */
    @Test
    void aThresholdAboveTheBestScoreRetrievesNothing() {
        List<Document> unbounded = store.similaritySearch(SearchRequest.builder()
                .query("pto")
                .topK(3)
                .similarityThresholdAll()
                .build());
        assertTrue(unbounded.size() > 1, "expected several chunks above a zero floor");
        double best = unbounded.get(0).getScore();

        List<Document> floored = store.similaritySearch(SearchRequest.builder()
                .query("pto")
                .topK(3)
                .similarityThreshold(best + 0.01)
                .build());
        assertTrue(floored.isEmpty(),
                "a floor of " + best + " should drop every chunk, got " + floored.size() + " back");
    }
}
