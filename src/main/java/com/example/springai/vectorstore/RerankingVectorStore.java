package com.example.springai.vectorstore;

import com.example.springai.rag.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

/**
 * A {@link VectorStore} that re-ranks a fused result before trimming it to {@code topK}.
 *
 * <p>It sits above the {@link HybridVectorStore} rather than inside it so the fusion class stays a pure
 * ranking problem with no model dependency, and so the retrieval that guards an answer can be taken from
 * the fusion layer directly — the CRAG coverage check wants the fused ranking, not the re-ranker's opinion
 * of it. Every caller that asks the container for a {@code VectorStore} gets this one, so the QA advisor,
 * {@code /rag/search} and the knowledge-base tool all see the re-ranked order without knowing it.
 *
 * <p>It only changes <em>order</em>: the documents returned are the delegate's own, with their cosine
 * scores intact. When the pool is no larger than the requested {@code topK} there is nothing to reorder and
 * the delegate is returned untouched, which is also the whole behaviour when re-ranking is disabled.
 */
public class RerankingVectorStore implements VectorStore, KeywordLegRebuildable {

    private static final Logger log = LoggerFactory.getLogger("RERANK");

    private final HybridVectorStore delegate;
    private final Reranker reranker;
    private final boolean enabled;
    private final int candidates;

    public RerankingVectorStore(HybridVectorStore delegate, Reranker reranker, boolean enabled, int candidates) {
        this.delegate = delegate;
        this.reranker = reranker;
        this.enabled = enabled;
        this.candidates = Math.max(1, candidates);
    }

    @Override
    public String getName() {
        return "RerankingVectorStore";
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> ids) {
        delegate.delete(ids);
    }

    @Override
    public void delete(Filter.Expression expression) {
        delegate.delete(expression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (!enabled || request.getTopK() <= 0) {
            return delegate.similaritySearch(request);
        }
        int wide = Math.max(request.getTopK(), candidates);
        List<Document> pool = delegate.similaritySearch(SearchRequest.from(request).topK(wide).build());
        if (pool.size() <= request.getTopK()) {
            return pool;
        }
        List<Document> ranked;
        try {
            ranked = reranker.rerank(request.getQuery(), pool);
        } catch (RuntimeException ex) {
            log.warn("[RERANK] re-rank failed, keeping the fused order: {}", ex.getMessage());
            ranked = pool;
        }
        if (ranked == null || ranked.isEmpty()) {
            ranked = pool;
        }
        return ranked.stream().limit(request.getTopK()).toList();
    }

    @Override
    public void rebuildKeywordIndex() {
        delegate.rebuildKeywordIndex();
    }
}
