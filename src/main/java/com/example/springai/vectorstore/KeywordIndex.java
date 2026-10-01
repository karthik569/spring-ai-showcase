package com.example.springai.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Predicate;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A BM25 index over the chunks held in the vector store.
 *
 * <p>It exists because a vector model is weak exactly where a keyword model is strong: a short, rare token
 * such as an acronym carries almost no signal to embed, so {@code "pto"} can rank the leave-policy chunk
 * below unrelated prose. BM25 scores that same query on the literal term and finds it at once. Keeping both
 * and fusing their rankings is the point of {@link HybridVectorStore}.
 *
 * <p>In memory and rebuilt from the store at boot: the corpus is a handful of policy chunks, so the index
 * costs nothing to hold and nothing to reconstruct.
 */
@Component
public class KeywordIndex {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int MIN_TERM_LENGTH = 2;

    private final Map<String, Document> documents = new HashMap<>();
    private final Map<String, Map<String, Integer>> termFrequencies = new HashMap<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final Map<String, Integer> lengths = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private long totalLength;

    public void add(List<Document> added) {
        lock.lock();
        try {
            for (Document document : added) {
                if (document.getText() != null && !document.getText().isBlank()) {
                    index(document);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void removeIds(List<String> ids) {
        lock.lock();
        try {
            ids.forEach(this::unindex);
        } finally {
            lock.unlock();
        }
    }

    /** Drops every indexed document the predicate matches — how a {@code delete(filter)} reaches the index. */
    public void removeMatching(Predicate<Document> predicate) {
        lock.lock();
        try {
            List<String> doomed = documents.values().stream()
                    .filter(predicate)
                    .map(Document::getId)
                    .toList();
            doomed.forEach(this::unindex);
        } finally {
            lock.unlock();
        }
    }

    /** Replaces the whole corpus, used after the vector store is restored from disk. */
    public void replaceAll(List<Document> replacement) {
        lock.lock();
        try {
            documents.clear();
            termFrequencies.clear();
            documentFrequency.clear();
            lengths.clear();
            totalLength = 0;
            for (Document document : replacement) {
                if (document.getText() != null && !document.getText().isBlank()) {
                    index(document);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return documents.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The documents matching the filter, ranked by BM25 descending. A document with no query term scores
     * zero and is left out, which is what stops a wholly unrelated chunk from entering the fusion.
     */
    public List<Scored> search(String query, Predicate<Document> filter) {
        Set<String> terms = terms(query);
        lock.lock();
        try {
            if (terms.isEmpty() || documents.isEmpty()) {
                return List.of();
            }
            int count = documents.size();
            double averageLength = count == 0 ? 1.0 : (double) totalLength / count;
            List<Scored> scored = new ArrayList<>();
            for (Document document : documents.values()) {
                if (filter != null && !filter.test(document)) {
                    continue;
                }
                double score = score(document, terms, count, averageLength);
                if (score > 0) {
                    scored.add(new Scored(document, score));
                }
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            return scored;
        } finally {
            lock.unlock();
        }
    }

    private double score(Document document, Set<String> terms, int count, double averageLength) {
        Map<String, Integer> frequencies = termFrequencies.getOrDefault(document.getId(), Map.of());
        int length = lengths.getOrDefault(document.getId(), 0);
        double score = 0;
        for (String term : terms) {
            Integer frequency = frequencies.get(term);
            if (frequency == null) {
                continue;
            }
            int frequencyAcrossCorpus = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1 + (count - frequencyAcrossCorpus + 0.5) / (frequencyAcrossCorpus + 0.5));
            double denominator = frequency + K1 * (1 - B + B * length / averageLength);
            score += idf * (frequency * (K1 + 1)) / denominator;
        }
        return score;
    }

    private void index(Document document) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String term : terms(document.getText())) {
            frequencies.merge(term, 1, Integer::sum);
        }
        int length = frequencies.values().stream().mapToInt(Integer::intValue).sum();
        documents.put(document.getId(), document);
        termFrequencies.put(document.getId(), frequencies);
        lengths.put(document.getId(), length);
        totalLength += length;
        frequencies.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
    }

    private void unindex(String id) {
        Map<String, Integer> frequencies = termFrequencies.remove(id);
        Document document = documents.remove(id);
        Integer length = lengths.remove(id);
        if (document == null || frequencies == null) {
            return;
        }
        if (length != null) {
            totalLength -= length;
        }
        frequencies.keySet().forEach(term -> documentFrequency.computeIfPresent(term, (key, value) -> value - 1));
    }

    private static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        for (String term : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (term.length() >= MIN_TERM_LENGTH) {
                terms.add(term);
            }
        }
        return terms;
    }

    /** A document and its BM25 score, the keyword half of what {@link HybridVectorStore} fuses. */
    public record Scored(Document document, double score) {
    }
}
