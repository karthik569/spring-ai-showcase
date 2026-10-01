package com.example.springai.rag;

import com.example.springai.SpringAiApplication;
import com.example.springai.rag.GoldenQuestions.GoldenQuestion;
import com.example.springai.service.RagDocumentIngestionService;
import com.example.springai.support.OllamaReachable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 3 of the eval: not "did retrieval find it", but "did the answer stay faithful to what retrieval
 * found". A retrieval tier cannot see a model that ignores its context or blends in training memory; a judge
 * call can.
 *
 * <p>Run explicitly, since it needs a live chat model and issues two prompts per question:
 * {@code mvn -B test -Dapp.rag.judge=true}. Off by default — a skipped class counts as green.
 *
 * <p>Scores are reported, never gated: a 1.5B judge is itself noisy, and a threshold here would fail on the
 * judge's mood. What is asserted is that every question produced a parseable score, which is the
 * infrastructure claim the tier depends on.
 */
@EnabledIfSystemProperty(named = "app.rag.judge", matches = "true")
@SpringBootTest(classes = SpringAiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:rag-judge;DB_CLOSE_DELAY=-1",
                "app.rag.store.enabled=false"})
class GroundednessJudgeTest {

    /** Bounded so an opt-in run finishes: each row is one answer plus one judge call on a phone-sized model. */
    private static final int MAX_QUESTIONS = 5;

    private static final String ANSWER_SYSTEM = """
            You answer strictly from the supplied context. If the context does not contain the answer, say so.
            Do not use outside knowledge.""";

    private static final String JUDGE_SYSTEM = """
            You are a strict grader. You will be given a CONTEXT and an ANSWER. Decide whether every factual
            claim in the ANSWER is supported by the CONTEXT. Reply with a single digit and nothing else:
            0 = contradicts the context, 1 = mostly unsupported, 2 = mostly supported, 3 = fully supported.""";

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagDocumentIngestionService ingestion;

    @Autowired
    private ChatClient chatClient;

    @Value("${app.rag.top-k:4}")
    private int topK;

    @Test
    void reportsAnswerGroundednessAgainstRetrievedContext() throws IOException {
        OllamaReachable.orSkip(vectorStore);
        ingestion.ingest(new ClassPathResource("rag/corpus/" + GoldenQuestions.RUNBOOK),
                GoldenQuestions.RUNBOOK, "test-corpus");

        List<GoldenQuestion> questions = GoldenQuestions.load().stream()
                .filter(question -> "match".equals(question.expect()))
                .limit(MAX_QUESTIONS)
                .toList();

        List<JudgeRow> rows = new ArrayList<>();
        for (GoldenQuestion question : questions) {
            List<Document> retrieved = GoldenQuestions.search(
                    vectorStore, question.question(), question.filename(), topK, 0.0);
            String context = retrieved.stream()
                    .map(Document::getText)
                    .filter(java.util.Objects::nonNull)
                    .reduce("", (a, b) -> a + "\n\n" + b);

            String answer = chatClient.prompt()
                    .system(ANSWER_SYSTEM)
                    .user("CONTEXT:\n" + context + "\n\nQUESTION: " + question.question())
                    .call()
                    .content();

            int score = judge(context, answer == null ? "" : answer);
            rows.add(new JudgeRow(question.id(), retrieved.size(), score, answer));
        }

        System.out.println(table(rows));
        System.out.println("Tier 3 reports; the digit above is a 1.5B judge's verdict, not a gate.");

        assertEquals(questions.size(), rows.size());
        rows.forEach(row -> assertTrue(row.score() >= 0 && row.score() <= 3,
                row.id() + " produced no parseable judge score"));
    }

    private int judge(String context, String answer) {
        String verdict = chatClient.prompt()
                .system(JUDGE_SYSTEM)
                .user("CONTEXT:\n" + context + "\n\nANSWER:\n" + answer)
                .call()
                .content();
        if (verdict == null) {
            return -1;
        }
        for (char c : verdict.toCharArray()) {
            if (c >= '0' && c <= '3') {
                return c - '0';
            }
        }
        return -1;
    }

    /** @param score 0..3, or -1 when the judge's reply carried no digit */
    private record JudgeRow(String id, int chunks, int score, String answer) {}

    private static String table(List<JudgeRow> rows) {
        StringBuilder report = new StringBuilder("\nLLM-as-judge groundedness (1.5B grading 1.5B)\n");
        double total = 0;
        int scored = 0;
        for (JudgeRow row : rows) {
            report.append(String.format(Locale.ROOT, "%-28s chunks=%d  score=%s  answer=%s%n",
                    row.id(), row.chunks(), row.score() < 0 ? "unparsed" : String.valueOf(row.score()),
                    preview(row.answer())));
            if (row.score() >= 0) {
                total += row.score();
                scored++;
            }
        }
        report.append(String.format(Locale.ROOT, "average: %.2f over %d scored rows (max 3.00)%n",
                scored == 0 ? 0.0 : total / scored, scored));
        return report.toString();
    }

    private static String preview(String answer) {
        String collapsed = answer == null ? "" : answer.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 90 ? collapsed : collapsed.substring(0, 90) + "...";
    }
}
