package com.example.springai.vectorstore;

import com.example.springai.rag.Reranker;
import com.example.springai.rag.StubEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The re-rank decorator, asserted offline over a real {@link HybridVectorStore} and a scripted re-ranker.
 *
 * <p>The claim under test is narrow: it reorders the fused pool and trims to {@code topK}, it asks the
 * delegate for a wider pool than the caller wanted (so the re-ranker has something to choose from), it
 * changes nothing when disabled, and a re-ranker that fails leaves the fused order in place.
 */
class RerankingVectorStoreTest {

    @Test
    void reordersByTheRerankerAndLimitsToTopK() {
        HybridVectorStore hybrid = hybrid();
        hybrid.add(threeDocuments());
        AtomicReference<Integer> poolSize = new AtomicReference<>();
        Reranker reverse = (query, documents) -> {
            poolSize.set(documents.size());
            List<Document> reversed = new ArrayList<>(documents);
            Collections.reverse(reversed);
            return reversed;
        };
        RerankingVectorStore store = new RerankingVectorStore(hybrid, reverse, true, 12);

        List<Document> hits = store.similaritySearch(request(2));

        assertEquals(2, hits.size(), "the result is trimmed to topK");
        assertEquals(3, poolSize.get(), "the delegate is asked for the whole wider candidate pool");
    }

    @Test
    void aFailingRerankerKeepsTheFusedOrder() {
        HybridVectorStore hybrid = hybrid();
        hybrid.add(threeDocuments());
        RerankingVectorStore store = new RerankingVectorStore(hybrid, (query, documents) -> {
            throw new RuntimeException("model down");
        }, true, 12);

        List<Document> hits = store.similaritySearch(request(2));

        assertEquals(ids(hybrid.similaritySearch(request(2))), ids(hits),
                "a re-rank failure must not decide the order or drop a candidate");
    }

    @Test
    void disabledRerankingDelegatesUntouched() {
        HybridVectorStore hybrid = hybrid();
        hybrid.add(threeDocuments());
        AtomicInteger calls = new AtomicInteger();
        RerankingVectorStore store = new RerankingVectorStore(hybrid, (query, documents) -> {
            calls.incrementAndGet();
            return documents;
        }, false, 12);

        assertEquals(ids(hybrid.similaritySearch(request(2))), ids(store.similaritySearch(request(2))));
        assertEquals(0, calls.get(), "a disabled re-ranker must not be asked");
    }

    private static HybridVectorStore hybrid() {
        return new HybridVectorStore(SimpleVectorStore.builder(new StubEmbeddingModel()).build(),
                new KeywordIndex(), true, 50, 60);
    }

    private static List<Document> threeDocuments() {
        return List.of(
                document("d1", "annual leave policy twenty five days", "policy"),
                document("d2", "home office equipment stipend headphones", "policy"),
                document("d3", "parental leave sixteen weeks fully paid", "policy"));
    }

    private static SearchRequest request(int topK) {
        return SearchRequest.builder().query("leave").topK(topK).similarityThresholdAll().build();
    }

    private static Document document(String id, String text, String filename) {
        return Document.builder().id(id).text(text).metadata(Map.of("filename", filename)).build();
    }

    private static List<String> ids(List<Document> documents) {
        return documents.stream().map(Document::getId).toList();
    }
}
