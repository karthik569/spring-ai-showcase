package com.example.springai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The coverage gate's decision, asserted offline over a real stub-embedder store and a scripted grader.
 *
 * <p>The gate is what stands between a question and an answer invented from the model's memory. The claims
 * are: a relevant candidate grounds the answer; nothing relevant broadens the search once (using the caller's
 * own words) before giving up; and a grader that has no opinion never causes a refusal, because a false
 * refusal costs more than a mistaken answer here.
 */
class RagCoverageGateTest {

    @Test
    void aRelevantCandidateGroundsTheAnswer() {
        RagCoverageGate gate = gate(graderAll(), true);

        RagCoverageGate.Decision decision = gate.assess("how many leave days", "how many leave days", null);

        assertTrue(decision.answerable());
        assertEquals(RagCoverageGate.Outcome.GROUNDED, decision.outcome());
        assertEquals("how many leave days", decision.query(), "the resolved text is what gets searched");
        assertFalse(decision.candidates().isEmpty());
    }

    @Test
    void nothingRelevantBroadensOnceUsingTheRawQuestion() {
        // Relevant only when the caller's own words are searched, never the rewritten text.
        ContextGrader grader = (question, candidates) ->
                question.contains("parental") ? List.copyOf(candidates) : List.of();
        RagCoverageGate gate = gate(grader, true);

        RagCoverageGate.Decision decision =
                gate.assess("and how many weeks is that", "what is the parental leave entitlement", null);

        assertTrue(decision.answerable());
        assertEquals(RagCoverageGate.Outcome.RE_QUERIED, decision.outcome());
        assertEquals("what is the parental leave entitlement", decision.query(),
                "a broadened retry searches the caller's own words");
    }

    @Test
    void nothingRelevantAnywhereIsRefused() {
        RagCoverageGate gate = gate((question, candidates) -> List.of(), true);

        RagCoverageGate.Decision decision = gate.assess("unanswerable question", "unanswerable question", null);

        assertFalse(decision.answerable());
        assertEquals(RagCoverageGate.Outcome.REFUSED, decision.outcome());
        assertFalse(decision.candidates().isEmpty(),
                "the closest passages are still returned so a refusal can quote them");
    }

    @Test
    void aGraderWithNoOpinionDoesNotCauseARefusal() {
        // LlmContextGrader returns every candidate when its reply is unparseable: "no opinion" must fail open.
        RagCoverageGate gate = gate(graderAll(), true);

        RagCoverageGate.Decision decision = gate.assess("anything", "anything", null);

        assertTrue(decision.answerable(), "an unusable grader must not refuse a question it could not judge");
    }

    @Test
    void aDisabledGateLeavesTheAnswerPathAlone() {
        RagCoverageGate gate = gate((question, candidates) -> List.of(), false);

        RagCoverageGate.Decision decision = gate.assess("anything", "anything", null);

        assertTrue(decision.answerable());
        assertEquals(RagCoverageGate.Outcome.DISABLED, decision.outcome());
        assertTrue(decision.candidates().isEmpty());
    }

    private static ContextGrader graderAll() {
        return (question, candidates) -> List.copyOf(candidates);
    }

    private static RagCoverageGate gate(ContextGrader grader, boolean enabled) {
        SimpleVectorStore store = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        store.add(List.of(
                Document.builder().id("leave-1").text("Employees receive 25 days of annual leave and 16 weeks "
                                + "of parental leave.").metadata(Map.of("filename", "policy.md")).build()));
        return new RagCoverageGate(store, grader, enabled, 6, 1);
    }
}
