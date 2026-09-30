package com.example.springai.rag;

import com.example.springai.SpringAiApplication;
import com.example.springai.rag.GoldenQuestions.FollowUp;
import com.example.springai.rag.GoldenQuestions.FollowUpRow;
import com.example.springai.rag.GoldenQuestions.GoldenQuestion;
import com.example.springai.rag.GoldenQuestions.Row;
import com.example.springai.service.RagDocumentIngestionService;
import com.example.springai.support.OllamaReachable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 of the retrieval eval: the same dataset and the same metrics, against the real {@code all-minilm}
 * vectors and the store the application built at boot.
 *
 * <p>Run it explicitly, because it needs a live backend and its numbers move between runs:
 * {@code mvn -B test -Dapp.rag.eval=true}. Without that property the class is <em>skipped</em>, which counts
 * as green — {@code @EnabledIfSystemProperty} was chosen over a tag plus surefire configuration so that
 * opting in costs no change to the build file.
 *
 * <p>Ranks are printed, not asserted. The vector leg genuinely struggles here (an acronym query has almost
 * no content to embed) and the scores drift, so a gate on them would report the weather. What is asserted is
 * the model-independent half: a {@code nomatch} row must still have nothing indexed that answers it.
 */
@EnabledIfSystemProperty(named = "app.rag.eval", matches = "true")
@SpringBootTest(classes = SpringAiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Must be overridden: the file-backed chat memory is locked while the application itself is
                // running, and a tier-2 run is exactly the moment both are up.
                "spring.datasource.url=jdbc:h2:mem:rag-eval;DB_CLOSE_DELAY=-1",
                // Reading ./data/rag here would measure whatever the last manual upload left behind, so the
                // table would stop being reproducible from the repository alone.
                "app.rag.store.enabled=false"})
class LiveRetrievalComparisonTest {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagDocumentIngestionService ingestion;

    /** The floor the application actually serves with, because a follow-up's failure mode is defined by it. */
    @Value("${app.rag.similarity-threshold:0.2}")
    private double similarityThreshold;

    @Test
    void reportsLiveRetrievalRanks() throws IOException {
        // Probe first: with the backend down, the ingest below would throw and read as a failure rather than
        // the skip this class is designed to produce.
        OllamaReachable.orSkip(vectorStore);

        ingestion.ingest(new ClassPathResource("rag/corpus/" + GoldenQuestions.RUNBOOK),
                GoldenQuestions.RUNBOOK, "test-corpus");

        List<GoldenQuestion> questions = GoldenQuestions.load();
        List<Row> rows = GoldenQuestions.measure(vectorStore, questions);
        List<String> beyondBudget = GoldenQuestions.gate(rows);

        System.out.println(GoldenQuestions.table("live embedder (all-minilm)", rows, List.of()));
        System.out.println("Tier 2 reports; the rows above are against tier 1's rank budget, which is not a"
                + " gate here. Budget misses: "
                + (beyondBudget.isEmpty() ? "none" : "\n  - " + String.join("\n  - ", beyondBudget)));

        assertEquals(questions.size(), rows.size());
        rows.stream().filter(row -> "nomatch".equals(row.expect()))
                .forEach(row -> assertEquals(0, row.goldTotal(),
                        row.id() + " claims nothing answers it, yet a chunk carries its marker"));
        assertTrue(rows.stream().anyMatch(row -> "match".equals(row.expect()) && row.rank() > 0),
                "the live store answered nothing — is the embedding model running?");
    }

    /**
     * The follow-up table against the live vectors, reported and never gated.
     *
     * <p>Tier 1 asserts the shape of this dataset on the stub, where the numbers are deterministic. Here the
     * same rows move between runs: {@code all-minilm} puts two of these three raw follow-ups at rank 1 and the
     * third's gold chunk straddles the shipped 0.2 floor, so a rank or cosine assertion would fail on drift
     * rather than on a regression. What the print is for is the floor — the one live consequence Phase 0 found
     * and the reason this feature exists at all — so an operator changing
     * {@code app.rag.similarity-threshold} can see what the change costs before shipping it.
     */
    @Test
    void reportsLiveFollowUpRanksAgainstTheServedFloor() throws IOException {
        OllamaReachable.orSkip(vectorStore);

        List<FollowUp> questions = GoldenQuestions.loadFollowUps();
        List<FollowUpRow> rows = GoldenQuestions.measureFollowUps(vectorStore, questions, similarityThreshold);
        List<String> beyondBudget = GoldenQuestions.gateFollowUps(rows);

        System.out.println(GoldenQuestions.followUpTable("live embedder (all-minilm)", similarityThreshold,
                rows, List.of()));
        System.out.println("Report-only tier: the budgets above are tier 1's. Rows that would miss them: "
                + (beyondBudget.isEmpty() ? "none" : "\n  - " + String.join("\n  - ", beyondBudget)));

        assertEquals(questions.size(), rows.size());
    }
}
