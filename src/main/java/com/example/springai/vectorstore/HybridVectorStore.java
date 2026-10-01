package com.example.springai.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A {@link VectorStore} that ranks by vector similarity and lexical overlap together.
 *
 * <p>It is a decorator, not a replacement: the vector search is still the source of truth for a document's
 * reported {@code score} (its cosine), and this class only changes <em>order</em> and <em>membership</em>.
 * Because it implements the same interface, every caller — the {@code QuestionAnswerAdvisor}, {@code
 * /rag/search}, the knowledge-base tool — gets hybrid retrieval for free, and the wiring never learns it is
 * talking to a decorator.
 *
 * <p>The two ranked lists are merged with reciprocal rank fusion, which needs no score calibration between
 * a cosine and a BM25 number. A document is eligible if the vector leg put it at or above the request's
 * threshold <em>or</em> the keyword leg matched a query term: the union is what recovers the acronym chunk
 * the cosine floor would have dropped.
 *
 * <p>The corpus is small enough that the vector leg is run over a pool at least as large as the index, so a
 * keyword-only document normally still carries a real cosine; only a chunk beyond the pool cap would report
 * a null score.
 */
public class HybridVectorStore implements VectorStore, KeywordLegRebuildable {

    private static final Logger log = LoggerFactory.getLogger(HybridVectorStore.class);

    // A constant probe text used only to enumerate the store; the scores it returns are discarded.
    private static final String SCAN_QUERY = "policy runbook leave budget travel";
    private static final int SCAN_DEPTH = 1000;
    private static final int MAX_POOL = 200;

    private final SimpleVectorStore inner;
    private final KeywordIndex keywordIndex;
    private final boolean enabled;
    private final int candidatePool;
    private final int rrfK;

    public HybridVectorStore(SimpleVectorStore inner, KeywordIndex keywordIndex,
                             boolean enabled, int candidatePool, int rrfK) {
        this.inner = inner;
        this.keywordIndex = keywordIndex;
        this.enabled = enabled;
        this.candidatePool = candidatePool;
        this.rrfK = rrfK;
    }

    @Override
    public String getName() {
        return "HybridVectorStore";
    }

    @Override
    public void add(List<Document> documents) {
        inner.add(documents);
        keywordIndex.add(documents);
    }

    @Override
    public void delete(List<String> ids) {
        inner.delete(ids);
        keywordIndex.removeIds(ids);
    }

    @Override
    public void delete(Filter.Expression expression) {
        inner.delete(expression);
        keywordIndex.removeMatching(MetadataFilters.toPredicate(expression));
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (!enabled || request.getQuery() == null || request.getQuery().isBlank() || keywordIndex.size() == 0) {
            return inner.similaritySearch(request);
        }

        // The pool has to be at least the size of the corpus, or a document the keyword leg wants would have
        // no cosine to report and the threshold test below could never see it.
        int pool = Math.min(MAX_POOL,
                Math.max(candidatePool, Math.max(request.getTopK(), keywordIndex.size())));
        List<Document> vectorHits = inner.similaritySearch(SearchRequest.from(request)
                .topK(pool)
                .similarityThresholdAll()
                .build());

        Predicate<Document> filter = MetadataFilters.toPredicate(request.getFilterExpression());
        List<KeywordIndex.Scored> keywordHits = keywordIndex.search(request.getQuery(), filter);

        Map<String, Integer> vectorRank = new HashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            Document document = vectorHits.get(i);
            vectorRank.putIfAbsent(document.getId(), i + 1);
            byId.putIfAbsent(document.getId(), document);
        }
        Map<String, Integer> keywordRank = new HashMap<>();
        for (int i = 0; i < keywordHits.size(); i++) {
            Document document = keywordHits.get(i).document();
            keywordRank.putIfAbsent(document.getId(), i + 1);
            byId.putIfAbsent(document.getId(), document);
        }

        double threshold = request.getSimilarityThreshold();
        return byId.values().stream()
                .filter(document -> eligible(document, threshold, keywordRank.containsKey(document.getId())))
                .sorted(Comparator.comparingDouble(
                        (Document document) -> fused(vectorRank.get(document.getId()),
                                keywordRank.get(document.getId()))).reversed())
                .limit(request.getTopK())
                .toList();
    }

    /**
     * Rebuilds the keyword index from the store's current contents.
     *
     * <p>Needed once per boot, after the store has been restored from disk: a restore loads chunks straight
     * into the inner store, bypassing {@link #add} and therefore the index. Called from the ingestion runner
     * so it happens after both the restore and the classpath refresh.
     */
    public void rebuildKeywordIndex() {
        try {
            List<Document> all = inner.similaritySearch(SearchRequest.builder()
                    .query(SCAN_QUERY)
                    .topK(SCAN_DEPTH)
                    .similarityThresholdAll()
                    .build());
            keywordIndex.replaceAll(all);
            log.info("[HYBRID] Keyword index rebuilt over {} chunk(s)", all.size());
        } catch (Exception ex) {
            // An empty index only disables the keyword leg; the vector leg still answers, so this is a
            // degradation and not a boot failure.
            log.warn("[HYBRID] Keyword index rebuild skipped, the embedder did not answer: {}", ex.getMessage());
        }
    }

    private static boolean eligible(Document document, double threshold, boolean keywordHit) {
        Double cosine = document.getScore();
        return keywordHit || (cosine != null && cosine >= threshold);
    }

    private double fused(Integer vectorRank, Integer keywordRank) {
        double score = 0;
        if (vectorRank != null) {
            score += 1.0 / (rrfK + vectorRank);
        }
        if (keywordRank != null) {
            score += 1.0 / (rrfK + keywordRank);
        }
        return score;
    }
}
