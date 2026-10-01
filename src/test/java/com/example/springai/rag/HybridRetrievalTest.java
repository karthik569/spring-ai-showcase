package com.example.springai.rag;

import com.example.springai.rag.GoldenQuestions.GoldenQuestion;
import com.example.springai.rag.GoldenQuestions.Row;
import com.example.springai.service.RagDocumentIngestionService;
import com.example.springai.service.RagStorePersistence;
import com.example.springai.vectorstore.HybridVectorStore;
import com.example.springai.vectorstore.KeywordIndex;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hybrid retrieval, asserted offline with the deterministic stub embedder.
 *
 * <p>The corpus is inverted in places — a real embedding model is weakest on short acronyms — so the point
 * here is the mechanism, not a production score: when the cosine floor drops a chunk whose literal term the
 * query contains, the BM25 leg must bring it back, and when the query's terms are absent the two legs must
 * agree on nothing. The last test runs the same code over the real corpus to prove the fusion did not push
 * any already-correct gold chunk off its budget.
 */
class HybridRetrievalTest {

    private static final double COSINE_FLOOR = 0.5;

    @Test
    void keywordIndexRanksTheChunkContainingTheLiteralTerm() {
        KeywordIndex index = new KeywordIndex();
        index.add(List.of(
                document("d1", "pto annual leave allowance", "policy"),
                document("d2", "home office equipment stipend", "policy")));

        List<KeywordIndex.Scored> hits = index.search("pto", document -> true);

        assertEquals(1, hits.size(), "only the chunk holding the term should match");
        assertEquals("d1", hits.get(0).document().getId());
        assertTrue(hits.get(0).score() > 0, "a matched term must score above zero");
    }

    @Test
    void hybridRecoversAChunkTheCosineFloorWouldDrop() {
        SimpleVectorStore inner = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        HybridVectorStore hybrid = new HybridVectorStore(inner, new KeywordIndex(), true, 50, 60);
        hybrid.add(List.of(
                document("d1", "pto annual leave twenty five days", "policy"),
                document("d2", "home office equipment stipend headphones", "policy"),
                document("d3", "parental leave sixteen weeks fully paid", "policy")));

        SearchRequest request = SearchRequest.builder()
                .query("pto")
                .topK(3)
                .similarityThreshold(COSINE_FLOOR)
                .build();

        // The single shared term puts d1's cosine under the floor, so pure vector search finds nothing.
        assertTrue(inner.similaritySearch(request).isEmpty(),
                "the fixture assumes the acronym scores below the floor on the vector leg");

        List<Document> hits = hybrid.similaritySearch(request);

        assertEquals(1, hits.size());
        assertEquals("d1", hits.get(0).getId(), "the keyword leg must recover the chunk for the acronym");
        // The reported score stays the cosine: hybrid changes membership and order, not score semantics.
        assertTrue(hits.get(0).getScore() < COSINE_FLOOR);
    }

    @Test
    void hybridAppliesTheFilenameFilterToBothLegs() {
        SimpleVectorStore inner = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        HybridVectorStore hybrid = new HybridVectorStore(inner, new KeywordIndex(), true, 50, 60);
        hybrid.add(List.of(
                document("d1", "pto annual leave allowance", "a"),
                document("d2", "pto parental leave policy", "b")));

        SearchRequest request = SearchRequest.builder()
                .query("pto")
                .topK(5)
                .similarityThresholdAll()
                .filterExpression(new FilterExpressionBuilder().eq("filename", "b").build())
                .build();

        List<Document> hits = hybrid.similaritySearch(request);

        assertEquals(1, hits.size(), "the filter must exclude the other document");
        assertEquals("d2", hits.get(0).getId());
    }

    @Test
    void hybridDoesNotRegressTheGoldenRanking() throws IOException {
        SimpleVectorStore inner = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        HybridVectorStore hybrid = new HybridVectorStore(inner, new KeywordIndex(), true, 50, 60);
        RagDocumentIngestionService ingestion = new RagDocumentIngestionService(hybrid,
                new RagStorePersistence(inner, false, "target/rag-hybrid-disabled", false));
        ingestion.ingest(new ClassPathResource("docs/" + GoldenQuestions.POLICY),
                GoldenQuestions.POLICY, "classpath");
        ingestion.ingest(new ClassPathResource("rag/corpus/" + GoldenQuestions.RUNBOOK),
                GoldenQuestions.RUNBOOK, "test-corpus");

        List<GoldenQuestion> questions = GoldenQuestions.load();
        List<Row> rows = GoldenQuestions.measure(hybrid, questions);
        List<String> failures = GoldenQuestions.gate(rows);

        assertTrue(failures.isEmpty(),
                "fusion reordered a gold chunk off its budget:\n"
                        + GoldenQuestions.table("stub embedder + BM25", rows, failures));
    }

    private static Document document(String id, String text, String filename) {
        return Document.builder().id(id).text(text).metadata(Map.of("filename", filename)).build();
    }
}
