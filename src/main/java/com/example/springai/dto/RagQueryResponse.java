package com.example.springai.dto;

import java.util.List;

/**
 * @param question what the caller asked, unchanged
 * @param resolvedQuestion the text that actually went into the vector search. Always present, even when it
 *                         equals {@code question}: the citation list is only honest if the reader can see what
 *                         was searched, otherwise a follow-up can advertise chunks fetched against a pronoun.
 * @param followUpResolved true when the model substituted an antecedent, i.e. {@code resolvedQuestion} differs
 * @param followUpOutcome why the query is or is not the caller's own text — {@code DISABLED},
 *                        {@code NO_CONVERSATION}, {@code EMPTY_MEMORY}, {@code SKIPPED_BY_TRIGGER},
 *                        {@code PASSTHROUGH}, {@code REWRITTEN}
 * @param rewriteTimeMs the second model call's cost, so the latency of resolution is a number a reader can
 *                      check rather than a claim in a README
 * @param answer the model's reply
 * @param sourceDocuments the chunks the advisor actually injected, taken from its own retrieval context
 * @param groundingOutcome whether the knowledge base covered the question — {@code GROUNDED} (the retrieved
 *                         chunks answer it), {@code RE_QUERIED} (only after dropping the rewrite and the
 *                         filename filter), {@code REFUSED} (nothing relevant; {@code answer} is a refusal
 *                         and the model was not asked), or {@code DISABLED} (the gate is off)
 * @param responseTimeMs wall time for the whole request, resolution included
 */
public record RagQueryResponse(
        String question,
        String resolvedQuestion,
        boolean followUpResolved,
        String followUpOutcome,
        long rewriteTimeMs,
        String answer,
        List<RetrievedChunk> sourceDocuments,
        String groundingOutcome,
        long responseTimeMs
) {}
