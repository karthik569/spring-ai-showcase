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
        return doc -> question.filename().equals(String.valueOf(doc.getMetadata().get("filename")))
                && doc.getText() != null && doc.getText().contains(question.marker());
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

    private static List<Document> retrieve(VectorStore store, GoldenQuestion question) {
        return store.similaritySearch(SearchRequest.builder()
                .query(question.question())
                .topK(question.topK())
                .similarityThresholdAll()
                .filterExpression(new FilterExpressionBuilder().eq("filename", question.filename()).build())
                .build());
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
