package com.example.springai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The golden-question dataset and the one way to measure it, shared by tier 1 (stub embedder, asserted) and
 * tier 2 (live embedder, reported).
 *
 * <p>Both tiers search with {@code similarityThresholdAll()}: the floor is a production knob, and a metric
 * that could be moved by it would measure configuration instead of retrieval. The 0.2 floor itself is
 * asserted separately, in {@code GoldenQuestionsTest#aThresholdAboveTheBestScoreRetrievesNothing}.
 */
public final class GoldenQuestions {

    public static final String POLICY = "company-policy.md";
    public static final String RUNBOOK = "ops-runbook.md";

    private static final int SCAN_DEPTH = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoldenQuestions() {
    }

    /**
     * @param maxFirstHitRank 1-based budget for a {@code match} row; always 0 for {@code nomatch}
     * @param expect          {@code match} or {@code nomatch}
     */
    public record GoldenQuestion(String id, String question, String filename, String marker,
                                 int topK, int maxFirstHitRank, String expect) {
    }

    /** A row is the outcome for one question; the report is what makes a regression attributable. */
    public record Row(String id, String expect, int rank, int maxFirstHitRank, double recall,
                      int goldInTop, int goldTotal, Double closest) {
    }

    public static List<GoldenQuestion> load() throws IOException {
        try (InputStream in = new ClassPathResource("rag/golden-questions.json").getInputStream()) {
            List<GoldenQuestion> questions = new ArrayList<>();
            for (JsonNode node : MAPPER.readTree(in)) {
                questions.add(new GoldenQuestion(
                        node.path("id").asText(),
                        node.path("question").asText(),
                        node.path("filename").asText(),
                        node.path("marker").asText(),
                        node.path("topK").asInt(3),
                        node.path("maxFirstHitRank").asInt(1),
                        node.path("expect").asText("match")));
            }
            return questions;
        }
    }

    /**
     * A chunk is gold when it belongs to the document the question is filtered to and literally carries the
     * answer text, so the check is provenance rather than a human reading of an embedding.
     */
    public static Predicate<Document> gold(GoldenQuestion question) {
        return gold(question.filename(), question.marker());
    }

    /** The same rule with the parts spelled out, for rows that are not {@link GoldenQuestion}s. */
    public static Predicate<Document> gold(String filename, String marker) {
        return doc -> filename.equals(String.valueOf(doc.getMetadata().get("filename")))
                && doc.getText() != null && doc.getText().contains(marker);
    }

    public static List<Row> measure(VectorStore store, List<GoldenQuestion> questions) {
        List<Row> rows = new ArrayList<>();
        for (GoldenQuestion question : questions) {
            Predicate<Document> gold = gold(question);
            int goldTotal = RetrievalMetrics.countGold(scan(store, question.filename()), gold);
            List<Document> top = retrieve(store, question);
            rows.add(new Row(question.id(), question.expect(),
                    RetrievalMetrics.firstHitRank(top, gold), question.maxFirstHitRank(),
                    RetrievalMetrics.recallAtK(top, gold, question.topK(), goldTotal),
                    RetrievalMetrics.countGold(top, gold), goldTotal,
                    top.isEmpty() ? null : top.get(0).getScore()));
        }
        return rows;
    }

    public static List<String> gate(List<Row> rows) {
        List<String> failures = new ArrayList<>();
        for (Row row : rows) {
            if ("nomatch".equals(row.expect())) {
                if (row.goldTotal() != 0) {
                    failures.add(row.id() + " expects nothing indexed to answer it, but " + row.goldTotal()
                            + " chunk(s) still carry its marker");
                }
                if (row.rank() != 0) {
                    failures.add(row.id() + " expected no hit and got rank " + row.rank());
                }
                continue;
            }
            if (row.goldTotal() == 0) {
                failures.add(row.id() + " has no gold chunk — the dataset's marker matches nothing in the corpus");
            } else if (row.rank() == 0) {
                failures.add(row.id() + " retrieved no gold chunk within topK");
            } else if (row.rank() > row.maxFirstHitRank()) {
                failures.add(row.id() + " regressed: gold chunk at rank " + row.rank()
                        + ", budget " + row.maxFirstHitRank());
            } else if (row.recall() < 1.0) {
                failures.add(row.id() + " recall = " + RetrievalMetrics.format(row.recall()) + ", expected 1.000");
            }
        }
        return failures;
    }

    public static String table(String embedder, List<Row> rows, List<String> failures) {
        StringBuilder report = new StringBuilder("\n")
                .append(embedder).append(" — ").append(rows.size()).append(" golden questions, MRR=")
                .append(RetrievalMetrics.format(RetrievalMetrics.mrr(
                        rows.stream().filter(row -> "match".equals(row.expect()))
                                .map(Row::rank).filter(rank -> rank > 0).toList())))
                .append("\n");
        for (Row row : rows) {
            report.append(row.id() + "  " + row.expect() + "  rank=" + row.rank()
                    + " (budget " + row.maxFirstHitRank() + ")"
                    + "  recall=" + RetrievalMetrics.format(row.recall())
                    + "  gold=" + row.goldInTop() + "/" + row.goldTotal()
                    + "  closest=" + (row.closest() == null ? "none" : RetrievalMetrics.format(row.closest()))
                    + "\n");
        }
        if (!failures.isEmpty()) {
            report.append("\nFailures:\n  - ").append(String.join("\n  - ", failures)).append("\n");
        }
        return report.toString();
    }

    /**
     * A follow-up plus the antecedent it depends on. Separate rows from {@link GoldenQuestion} because the
     * shipped 15 have rank budgets calibrated by running them, and a follow-up row has to be able to expect a
     * <em>miss</em> unresolved — a budget of 0 means something different in each dataset.
     */
    public record FollowUp(String id, String followUp, String contextQuestion, String contextAnswer,
                           String resolved, String filename, String marker, int topK,
                           String expectRaw, int maxFirstHitRankResolved) {
    }

    /**
     * @param rawRank     1-based rank of the gold chunk for the question as typed; 0 means the floor dropped it
     * @param rawGold     the gold chunk's cosine under the raw question, or {@code NaN} when nothing carried it
     * @param expectRaw   copied from the row: {@code hit} or {@code miss}
     */
    public record FollowUpRow(String id, int rawRank, double rawGold, int resolvedRank, double resolvedGold,
                              String expectRaw, int maxFirstHitRankResolved) {
    }

    public static List<FollowUp> loadFollowUps() throws IOException {
        try (InputStream in = new ClassPathResource("rag/follow-up-questions.json").getInputStream()) {
            List<FollowUp> rows = new ArrayList<>();
            for (JsonNode node : MAPPER.readTree(in)) {
                rows.add(new FollowUp(
                        node.path("id").asText(),
                        node.path("followUp").asText(),
                        node.path("contextQuestion").asText(),
                        node.path("contextAnswer").asText(),
                        node.path("resolved").asText(),
                        node.path("filename").asText(),
                        node.path("marker").asText(),
                        node.path("topK").asInt(3),
                        node.path("expectRaw").asText("hit"),
                        node.path("maxFirstHitRankResolved").asInt(1)));
            }
            return rows;
        }
    }

    /**
     * Both forms of every row, searched the way the application searches: the configured floor, filtered to the
     * document the row names. The floor is a parameter here because it is the thing a follow-up can fall under
     * — measuring at 0.0 would hide the only failure this dataset is able to see.
     */
    public static List<FollowUpRow> measureFollowUps(VectorStore store, List<FollowUp> rows, double floor) {
        List<FollowUpRow> measured = new ArrayList<>();
        for (FollowUp row : rows) {
            Predicate<Document> gold = gold(row.filename(), row.marker());
            List<Document> raw = search(store, row.followUp(), row.filename(), row.topK(), floor);
            List<Document> resolved = search(store, row.resolved(), row.filename(), row.topK(), floor);
            measured.add(new FollowUpRow(row.id(),
                    RetrievalMetrics.firstHitRank(raw, gold), goldScore(raw, gold),
                    RetrievalMetrics.firstHitRank(resolved, gold), goldScore(resolved, gold),
                    row.expectRaw(), row.maxFirstHitRankResolved()));
        }
        return measured;
    }

    public static List<String> gateFollowUps(List<FollowUpRow> rows) {
        List<String> failures = new ArrayList<>();
        for (FollowUpRow row : rows) {
            if ("miss".equals(row.expectRaw())) {
                if (row.rawRank() != 0) {
                    failures.add(row.id() + " expected the unresolved follow-up to retrieve nothing at the"
                            + " configured floor, but its gold chunk came back at rank " + row.rawRank());
                }
            } else if (row.rawRank() == 0) {
                failures.add(row.id() + " expected the unresolved follow-up to still retrieve its gold chunk,"
                        + " but nothing under the floor carried the marker");
            }
            if (row.resolvedRank() == 0) {
                failures.add(row.id() + " resolved to text that retrieves nothing: " + row.id());
            } else if (row.resolvedRank() > row.maxFirstHitRankResolved()) {
                failures.add(row.id() + " regressed: resolved gold chunk at rank " + row.resolvedRank()
                        + ", budget " + row.maxFirstHitRankResolved());
            }
            // The claim that survives on every row, including the ones that already rank 1 unresolved. A raw
            // gold that the floor dropped has no cosine to compare against — finding it at all is the win, and
            // resolvedRank above already proves it.
            if (Double.isNaN(row.rawGold())) {
                if (Double.isNaN(row.resolvedGold())) {
                    failures.add(row.id() + " neither form retrieved its gold chunk under the floor");
                }
            } else if (Double.isNaN(row.resolvedGold()) || row.resolvedGold() < row.rawGold()) {
                failures.add(row.id() + " resolution did not raise the margin above the floor: raw gold "
                        + RetrievalMetrics.format(row.rawGold()) + " vs resolved gold "
                        + (Double.isNaN(row.resolvedGold()) ? "dropped"
                           : RetrievalMetrics.format(row.resolvedGold())));
            }
        }
        return failures;
    }

    public static String followUpTable(String embedder, double floor, List<FollowUpRow> rows, List<String> failures) {
        StringBuilder report = new StringBuilder("\n")
                .append(embedder).append(" — ").append(rows.size()).append(" follow-ups, similarity floor ")
                .append(RetrievalMetrics.format(floor)).append("\n");
        for (FollowUpRow row : rows) {
            report.append(String.format(java.util.Locale.ROOT,
                    "%-28s raw rank %d (expects %s, gold %s)   resolved rank %d (budget %d, gold %s)%n",
                    row.id(), row.rawRank(), row.expectRaw(), score(row.rawGold()),
                    row.resolvedRank(), row.maxFirstHitRankResolved(), score(row.resolvedGold())));
        }
        if (!failures.isEmpty()) {
            report.append("\nFailures:\n  - ").append(String.join("\n  - ", failures)).append("\n");
        }
        return report.toString();
    }

    private static String score(double value) {
        return Double.isNaN(value) ? "dropped" : RetrievalMetrics.format(value);
    }

    private static double goldScore(List<Document> hits, Predicate<Document> gold) {
        return hits.stream()
                .filter(gold)
                .map(Document::getScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .findFirst()
                .orElse(Double.NaN);
    }

    /** One search, the way the application searches: a floor of 0 means "no floor at all". */
    public static List<Document> search(VectorStore store, String query, String filename, int topK, double floor) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        builder = floor > 0 ? builder.similarityThreshold(floor) : builder.similarityThresholdAll();
        if (filename != null) {
            builder.filterExpression(new FilterExpressionBuilder().eq("filename", filename).build());
        }
        return store.similaritySearch(builder.build());
    }

    private static List<Document> retrieve(VectorStore store, GoldenQuestion question) {
        return search(store, question.question(), question.filename(), question.topK(), 0);
    }

    /** Everything indexed under one filename, which is how a gold total is counted without a store scan API. */
    private static List<Document> scan(VectorStore store, String filename) {
        return store.similaritySearch(SearchRequest.builder()
                .query("policy runbook leave budget")
                .topK(SCAN_DEPTH)
                .similarityThresholdAll()
                .filterExpression(new FilterExpressionBuilder().eq("filename", filename).build())
                .build());
    }
}
