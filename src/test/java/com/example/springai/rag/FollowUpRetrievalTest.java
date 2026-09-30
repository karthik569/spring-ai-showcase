package com.example.springai.rag;

import com.example.springai.rag.GoldenQuestions.FollowUp;
import com.example.springai.rag.GoldenQuestions.FollowUpRow;
import com.example.springai.service.RagDocumentIngestionService;
import com.example.springai.service.RagQueryResolver;
import com.example.springai.service.RagQueryResolver.Outcome;
import com.example.springai.service.RagQueryResolver.Resolution;
import com.example.springai.service.RagStorePersistence;
import com.example.springai.support.RecordingChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a follow-up actually retrieves, offline: the real splitter, the real {@link SimpleVectorStore}, the real
 * filter and threshold code, a stub embedder, and a rewriter that returns a fixture instead of calling Ollama.
 *
 * <p>Phase 0 (<code>docs/SESSION-HANDOFF.md</code>) falsified the obvious version of the premise — two of the
 * three follow-ups here already rank their gold chunk first unresolved, on both embedders. So this class asserts
 * the consequence that measurement did support: <em>the shipped similarity floor</em>. "how long do I have to
 * submit that?" scores 0.070 on the stub and 0.166 on live {@code all-minilm} against a chunk that clearly
 * answers it, which is under {@code app.rag.similarity-threshold: 0.2}, so the advisor injects nothing (stub) or
 * injects a chunk from a different section (live) and the citation list still looks plausible. Resolving the
 * pronoun lifts that same chunk to 0.248 / 0.588 and it is retrieved.
 *
 * <p>Rank stays the gate for the resolved form; the raw form's rank is asserted only where the row says
 * {@code "expectRaw": "miss"}, and the third assertion — that resolution never <em>lowers</em> the gold chunk's
 * cosine — is the one that holds for every row on every embedder.
 */
class FollowUpRetrievalTest {

    /** application.yml's {@code app.rag.similarity-threshold}. This test is about that number. */
    private static final double SHIPPED_FLOOR = 0.2;

    private static final String CONVERSATION = "rag-follow-up";

    private SimpleVectorStore store;

    /** The rewriter built by the most recent {@link #resolver} call, so prompt assertions have something to read. */
    private RecordingChatClient recorder;

    @BeforeEach
    void indexTheCorpus() throws IOException {
        store = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        // Persistence off, same as the other tiers: this measures retrieval, and writing a snapshot would tie
        // the measurement to the store's file format.
        RagDocumentIngestionService ingestion = new RagDocumentIngestionService(store,
                new RagStorePersistence(store, false, "target/rag-follow-up-disabled", false));
        ingestion.ingest(new ClassPathResource("docs/" + GoldenQuestions.POLICY),
                GoldenQuestions.POLICY, "classpath");
    }

    @Test
    void aFollowUpThatFallsUnderTheFloorIsGroundedAgainOnceResolved() throws IOException {
        FollowUp row = missRow();
        ChatMemory memory = memoryWith(row);
        RagQueryResolver resolver = resolver(memory, row);

        Resolution resolution = resolver.resolve(row.followUp(), CONVERSATION);
        assertEquals(Outcome.REWRITTEN, resolution.outcome());
        // Without this line the rewrite is indistinguishable from one that ignored the conversation.
        assertTrue(recorder.lastPrompt().contains(row.contextAnswer()), "chat memory never reached the rewriter");

        List<Document> unresolved = GoldenQuestions.search(store, row.followUp(), row.filename(), row.topK(),
                SHIPPED_FLOOR);
        assertEquals(0, unresolved.size(), () -> "the unresolved follow-up was expected to retrieve nothing, but "
                + unresolved.size() + " chunk(s) cleared the floor — the failure this test exists for is gone");

        List<Document> resolved = GoldenQuestions.search(store, resolution.retrievalQuery(), row.filename(),
                row.topK(), SHIPPED_FLOOR);
        assertEquals(1, RetrievalMetrics.firstHitRank(resolved, gold(row)),
                () -> "resolution did not put the gold chunk first: " + ranks(resolved, row));
    }

    @Test
    void everyFollowUpRowIsGatedOnRankAndOnItsMarginAboveTheFloor() throws IOException {
        List<FollowUpRow> measured = GoldenQuestions.measureFollowUps(store, GoldenQuestions.loadFollowUps(),
                SHIPPED_FLOOR);
        List<String> failures = GoldenQuestions.gateFollowUps(measured);

        assertTrue(failures.isEmpty(),
                GoldenQuestions.followUpTable("stub embedder", SHIPPED_FLOOR, measured, failures));
    }

    /**
     * The loop the endpoint runs: turn one has no history so nothing is rewritten, its exchange is stored, and
     * turn two resolves against the answer the model itself gave. Asserted offline, but with the real
     * {@link ChatMemory} window rather than a mock, because the displacing behaviour of a six-message window is
     * part of what this feature changes for {@code /chat/memory}.
     */
    @Test
    void aRememberedRagTurnBecomesTheAntecedentForTheNextFollowUp() throws IOException {
        FollowUp row = missRow();
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(6)
                .build();
        RagQueryResolver resolver = resolver(memory, row);

        Resolution first = resolver.resolve(row.contextQuestion(), CONVERSATION);
        assertEquals(Outcome.EMPTY_MEMORY, first.outcome());
        assertEquals(row.contextQuestion(), first.retrievalQuery());
        resolver.remember(CONVERSATION, row.contextQuestion(), row.contextAnswer());

        Resolution second = resolver.resolve(row.followUp(), CONVERSATION);
        assertEquals(Outcome.REWRITTEN, second.outcome());
        List<Document> retrieved = GoldenQuestions.search(store, second.retrievalQuery(), row.filename(),
                row.topK(), SHIPPED_FLOOR);
        assertEquals(1, RetrievalMetrics.firstHitRank(retrieved, gold(row)),
                () -> "the second turn searched text that retrieves nothing: " + ranks(retrieved, row));
    }

    private RagQueryResolver resolver(ChatMemory memory, FollowUp row) {
        recorder = RecordingChatClient.returning(row.resolved());
        return new RagQueryResolver(recorder.client(), memory, true, 2, 300, false);
    }

    private static FollowUp missRow() throws IOException {
        return GoldenQuestions.loadFollowUps().stream()
                .filter(row -> "miss".equals(row.expectRaw()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("follow-up-questions.json lost its miss row"));
    }

    private static ChatMemory memoryWith(FollowUp row) {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(6)
                .build();
        memory.add(CONVERSATION, List.of(new UserMessage(row.contextQuestion()),
                new AssistantMessage(row.contextAnswer())));
        return memory;
    }

    private static Predicate<Document> gold(FollowUp row) {
        return GoldenQuestions.gold(row.filename(), row.marker());
    }

    private static String ranks(List<Document> hits, FollowUp row) {
        StringBuilder out = new StringBuilder(GoldenQuestions.POLICY + " hits for " + row.id() + ": ");
        hits.forEach(doc -> out.append(doc.getScore()).append(' ')
                .append(doc.getText(), 0, Math.min(40, doc.getText().length())).append(" | "));
        return out.toString();
    }
}
