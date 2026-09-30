package com.example.springai.service;

import com.example.springai.service.RagQueryResolver.Outcome;
import com.example.springai.service.RagQueryResolver.Resolution;
import com.example.springai.support.RecordingChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.client.ResourceAccessException;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide whether a model's rewrite is usable, asserted without Ollama.
 *
 * <p>Several cases here are behaviours {@code qwen2.5:0.5b-instruct} actually exhibited on this prompt while
 * Phase 0 was measured (see {@code docs/SESSION-HANDOFF.md}): echoing the input, emitting the fragment
 * {@code "to SUBMIT that?"}, and labelling its own output instead of obeying "reply SAME". The model is not the
 * authority on whether it succeeded — these rules are.
 */
class RagQueryResolverTest {

    private static final String CONVERSATION = "rag-1";
    private static final String STORED_QUESTION = "What is the equipment stipend?";
    private static final String STORED_ANSWER =
            "Employees are entitled to a $1,500 annual home office equipment stipend.";
    private static final String STIPEND_REWRITE =
            "What is the reimbursement window for the home office equipment stipend?";

    /** The rewriter built by the most recent {@link #resolver} call, so prompt assertions have something to read. */
    private RecordingChatClient rewriter;

    @Test
    void withoutAConversationIdNothingIsRewrittenAndTheModelIsNotCalled() {
        Resolution resolution = resolver(memoryWithOneTurn(), STIPEND_REWRITE)
                .resolve("How many leave days?", null);

        assertEquals("How many leave days?", resolution.retrievalQuery());
        assertEquals(Outcome.NO_CONVERSATION, resolution.outcome());
        assertTrue(rewriter.prompts().isEmpty(), "a request with no conversation must not cost a model call");
    }

    @Test
    void aBlankConversationIdIsNoConversationRatherThanAValidationError() {
        Resolution resolution = resolver(memoryWithOneTurn(), "How much parental leave is paid?")
                .resolve("and for caregivers?", "   ");

        assertEquals(Outcome.NO_CONVERSATION, resolution.outcome());
        assertEquals("and for caregivers?", resolution.retrievalQuery());
    }

    @Test
    void theFirstTurnOfAConversationHasNothingToResolveAgainst() {
        ChatMemory empty = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(6)
                .build();

        Resolution resolution = resolver(empty, "How much parental leave is paid?")
                .resolve("and for caregivers?", CONVERSATION);

        assertEquals(Outcome.EMPTY_MEMORY, resolution.outcome());
        assertTrue(rewriter.prompts().isEmpty());
    }

    @Test
    void aFollowUpIsRewrittenAgainstTheStoredTurnAndTheHistoryReachesThePrompt() {
        Resolution resolution = resolver(memoryWithOneTurn(), STIPEND_REWRITE)
                .resolve("how long do I have to submit that?", CONVERSATION);

        assertEquals(Outcome.REWRITTEN, resolution.outcome());
        assertEquals(STIPEND_REWRITE, resolution.retrievalQuery());
        assertTrue(resolution.followUpResolved());
        // The reason the rewrite can work at all: memory is read here, because the retrieval advisor cannot see
        // the messages the memory advisor injects.
        String prompt = rewriter.lastPrompt();
        assertTrue(prompt.contains(STORED_QUESTION), "the stored question is missing from the prompt");
        assertTrue(prompt.contains(STORED_ANSWER), "the stored answer is missing from the prompt");
        assertTrue(prompt.contains("how long do I have to submit that?"), "the new question is missing from the prompt");
    }

    @Test
    void turningTheFeatureOffTouchesNeitherModelNorMemory() {
        ChatMemory memory = memoryWithOneTurn();
        rewriter = RecordingChatClient.returning("anything");
        RagQueryResolver disabled = new RagQueryResolver(rewriter.client(), memory, false, 2, 300, false);

        Resolution resolution = disabled.resolve("and for caregivers?", CONVERSATION);

        assertEquals(Outcome.DISABLED, resolution.outcome());
        assertEquals("and for caregivers?", resolution.retrievalQuery());
        assertEquals(0, resolution.rewriteTimeMs());
        assertTrue(rewriter.prompts().isEmpty());

        disabled.remember(CONVERSATION, "and for caregivers?", "16 weeks.");
        assertEquals(2, memory.get(CONVERSATION).size(), "a disabled feature must not write history either");
    }

    @Test
    void anEchoedQuestionCountsAsNoChange() {
        Resolution resolution = resolver(memoryWithOneTurn(), " and what about travel? ")
                .resolve("and what about travel?", CONVERSATION);

        assertEquals(Outcome.PASSTHROUGH, resolution.outcome());
        assertEquals("and what about travel?", resolution.retrievalQuery());
    }

    @Test
    void anExplicitDeclineFromTheModelMeansNoChange() {
        Resolution resolution = resolver(memoryWithOneTurn(), "SAME").resolve("What is the PTO policy?", CONVERSATION);

        assertEquals(Outcome.PASSTHROUGH, resolution.outcome());
        assertEquals("What is the PTO policy?", resolution.retrievalQuery());
    }

    @Test
    void aLabelInTheOutputIsStrippedBeforeItReachesTheVectorStore() {
        Resolution resolution = resolver(memoryWithOneTurn(), "Rewritten question: How many PTO days are annual leave?")
                .resolve("how many PTO days?", CONVERSATION);

        assertEquals(Outcome.REWRITTEN, resolution.outcome());
        assertEquals("How many PTO days are annual leave?", resolution.retrievalQuery());
    }

    /** The label pattern requires punctuation, which is what keeps a legitimate sentence starting with "Same". */
    @Test
    void aRewriteThatMerelyStartsWithTheWordSameIsNotTreatedAsADecline() {
        Resolution resolution = resolver(memoryWithOneTurn(), "Same-day courier costs, what is the limit?")
                .resolve("and the delivery cost?", CONVERSATION);

        assertEquals(Outcome.REWRITTEN, resolution.outcome());
        assertEquals("Same-day courier costs, what is the limit?", resolution.retrievalQuery());
    }

    @Test
    void onlyTheFirstLineOfAMultiLineOutputIsUsed() {
        Resolution resolution = resolver(memoryWithOneTurn(),
                "How many days of annual leave are there?\nIt also mentions holidays.")
                .resolve("how many PTO days?", CONVERSATION);

        assertEquals(Outcome.REWRITTEN, resolution.outcome());
        assertEquals("How many days of annual leave are there?", resolution.retrievalQuery());
    }

    @Test
    void whitespaceOnlyOutputMeansNoChange() {
        Resolution resolution = resolver(memoryWithOneTurn(), "   \n  ")
                .resolve("and for caregivers?", CONVERSATION);

        assertEquals(Outcome.PASSTHROUGH, resolution.outcome());
        assertEquals("and for caregivers?", resolution.retrievalQuery());
    }

    /**
     * The measured failure: the model answered {@code "to SUBMIT that?"} to that exact question. It differs from
     * the input, so an equality check alone would search it — searching a fragment is worse than searching the
     * pronoun, which is why an unfinished substitution is discarded.
     */
    @Test
    void aRewriteThatStillCarriesTheDemonstrativeIsDiscarded() {
        Resolution resolution = resolver(memoryWithOneTurn(), "to SUBMIT that?")
                .resolve("how long do I have to submit that?", CONVERSATION);

        assertEquals(Outcome.PASSTHROUGH, resolution.outcome());
        assertEquals("how long do I have to submit that?", resolution.retrievalQuery());
    }

    /**
     * The over-rejection found by driving the running application on the very follow-up this feature exists for.
     * The model substituted the antecedent correctly and still wrote "How long does <em>it</em> take…", so a rule
     * keyed on the surviving word alone discarded a usable rewrite; the request then cited a chunk from the wrong
     * section at 0.219 and answered "I don't have enough context". Discarding is not a neutral fallback when the
     * fallback is the bug.
     */
    @Test
    void aPlaceholderPronounSurvivesOnceTheAntecedentIsNamed() {
        Resolution resolution = resolver(memoryWithOneTurn(),
                "How long does it take to submit the home office equipment stipend?")
                .resolve("how long do I have to submit that?", CONVERSATION);

        assertEquals(Outcome.REWRITTEN, resolution.outcome());
        assertEquals("How long does it take to submit the home office equipment stipend?",
                resolution.retrievalQuery());
    }

    /** The other half of the same rule: detail the conversation never mentioned is invention, not resolution. */
    @Test
    void aDemonstrativePairedWithAWordTheConversationNeverUsedIsDiscarded() {
        Resolution resolution = resolver(memoryWithOneTurn(), "How long do I have to submit that invoice?")
                .resolve("how long do I have to submit that?", CONVERSATION);

        assertEquals(Outcome.PASSTHROUGH, resolution.outcome());
        assertEquals("how long do I have to submit that?", resolution.retrievalQuery());
    }

    @Test
    void anOverlongRewriteIsCutAtASentenceBoundary() {
        // Shaped so the last space before the 300-character budget is the one after the question mark: the
        // overflow is a single unbroken token, which is how a rambling completion actually blows a budget.
        String standalone = STIPEND_REWRITE;

        Resolution resolution = resolver(memoryWithOneTurn(), standalone + " " + "x".repeat(260))
                .resolve("and the window?", CONVERSATION);

        assertEquals(Outcome.REWRITTEN, resolution.outcome());
        assertEquals(standalone, resolution.retrievalQuery());
    }

    @Test
    void anOverlongRewriteWithNoSentenceInsideTheBudgetIsNotSearchedAtAll() {
        String rambling = ("and what about the equipment budget for a standing desk and a monitor and headphones ")
                .repeat(6);

        Resolution resolution = resolver(memoryWithOneTurn(), rambling).resolve("and the budget?", CONVERSATION);

        assertEquals(Outcome.PASSTHROUGH, resolution.outcome());
        assertEquals("and the budget?", resolution.retrievalQuery());
    }

    @Test
    void anUnreachableBackendIsAFailedRequestNotAFallbackToThePronoun() {
        ChatClient down = (ChatClient) Proxy.newProxyInstance(ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.class}, (proxy, method, args) -> {
                    throw new ResourceAccessException("I/O error on POST request for \"http://localhost:11434\"");
                });
        RagQueryResolver resolver = new RagQueryResolver(down, memoryWithOneTurn(), true, 2, 300, false);

        // Falling back here would return a 200 whose citations were fetched against "that" — the very failure
        // this feature exists to remove.
        assertThrows(ResourceAccessException.class, () -> resolver.resolve("and for caregivers?", CONVERSATION));
    }

    @Test
    void withATriggerRequirementAQuestionThatAlreadyStandsAloneSkipsTheModel() {
        rewriter = RecordingChatClient.returning("How much paid parental leave is there?");
        RagQueryResolver resolver = new RagQueryResolver(rewriter.client(), memoryWithOneTurn(),
                true, 2, 300, true);

        Resolution resolution = resolver.resolve("What is the parental leave?", CONVERSATION);

        assertEquals(Outcome.SKIPPED_BY_TRIGGER, resolution.outcome());
        assertTrue(rewriter.prompts().isEmpty());
    }

    @Test
    void withATriggerRequirementADemonstrativeStillGetsRewritten() {
        rewriter = RecordingChatClient.returning("What is the reimbursement window for the equipment stipend?");
        RagQueryResolver resolver = new RagQueryResolver(rewriter.client(), memoryWithOneTurn(),
                true, 2, 300, true);

        Resolution resolution = resolver.resolve("how long do I have to file that?", CONVERSATION);

        assertEquals(Outcome.REWRITTEN, resolution.outcome());
    }

    @Test
    void rememberedTurnsStoreTheQuestionTheUserTypedAndTheAnswerThatFollowed() {
        ChatMemory memory = memoryWithOneTurn();

        resolver(memory, STIPEND_REWRITE).remember(CONVERSATION, "and for caregivers?", "16 weeks of fully paid leave.");

        List<Message> stored = memory.get(CONVERSATION);
        assertEquals(4, stored.size());
        assertEquals("and for caregivers?", stored.get(2).getText());
        assertEquals("16 weeks of fully paid leave.", stored.get(3).getText());
    }

    @Test
    void anEmptyAnswerIsNotStoredBecauseItWouldDisplaceARealAntecedent() {
        ChatMemory memory = memoryWithOneTurn();

        resolver(memory, STIPEND_REWRITE).remember(CONVERSATION, "and for caregivers?", "");

        assertEquals(2, memory.get(CONVERSATION).size());
    }

    @Test
    void onlyTheConfiguredNumberOfRecentTurnsIsSent() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(6)
                .build();
        memory.add(CONVERSATION, List.of(
                new UserMessage("oldest question about holidays"), new AssistantMessage("oldest answer about holidays"),
                new UserMessage("older question about monitors"), new AssistantMessage("older answer about monitors"),
                new UserMessage("recent question about chairs"), new AssistantMessage("recent answer about chairs")));

        rewriter = RecordingChatClient.returning("What is the limit for chairs?");
        new RagQueryResolver(rewriter.client(), memory, true, 1, 300, false)
                .resolve("and how much for that?", CONVERSATION);

        String prompt = rewriter.lastPrompt();
        assertTrue(prompt.contains("recent question about chairs"), "the newest turn should always be sent");
        assertTrue(!prompt.contains("oldest question"), "one turn of history means two messages, not six");
    }

    private RagQueryResolver resolver(ChatMemory memory, String rewriteOutput) {
        rewriter = RecordingChatClient.returning(rewriteOutput);
        return new RagQueryResolver(rewriter.client(), memory, true, 2, 300, false);
    }

    /** Seeds the conversation the rewrite is supposed to resolve against. */
    private static ChatMemory memoryWithOneTurn() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(6)
                .build();
        memory.add(CONVERSATION, List.of(new UserMessage(STORED_QUESTION), new AssistantMessage(STORED_ANSWER)));
        return memory;
    }
}
