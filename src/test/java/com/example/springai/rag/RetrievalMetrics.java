package com.example.springai.rag;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.function.Predicate;

/**
 * Rank-based retrieval metrics. Scores are reported for context but never asserted: they drift between
 * runs even for one embedder, whereas a rank flip is attributable to a code change.
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * @return the 1-based position of the first gold chunk, or 0 when the list holds none
     */
    public static int firstHitRank(List<Document> ranked, Predicate<Document> gold) {
        for (int i = 0; i < ranked.size(); i++) {
            if (gold.test(ranked.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * @param goldTotal how many gold chunks exist in the corpus, which a top-k list alone cannot tell you
     */
    public static double recallAtK(List<Document> ranked, Predicate<Document> gold, int k, int goldTotal) {
        if (goldTotal <= 0) {
            return 0;
        }
        int limit = Math.min(k, ranked.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (gold.test(ranked.get(i))) {
                hits++;
            }
        }
        return (double) hits / goldTotal;
    }

    public static int countGold(List<Document> documents, Predicate<Document> gold) {
        return (int) documents.stream().filter(gold).count();
    }

    /** Mean over {@code 1 / firstHitRank}, with a miss contributing 0. */
    public static double mrr(List<Integer> firstHitRanks) {
        if (firstHitRanks.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (int rank : firstHitRanks) {
            if (rank > 0) {
                sum += 1.0 / rank;
            }
        }
        return sum / firstHitRanks.size();
    }

    public static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
