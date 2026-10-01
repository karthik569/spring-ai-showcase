package com.example.springai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * A grader that asks the model, in one call, which of the candidate passages could answer the question.
 *
 * <p>The important behaviour is how it fails. A cosine floor cannot separate answerable from unanswerable
 * here — an unanswerable question has scored above three genuine matches — so the gate this feeds has to be
 * a relevance judgement, not a threshold. But a judgement that fails open is the only safe kind: a blank or
 * unparseable reply means "no opinion", and the caller must then <em>not</em> refuse a question the model
 * could have answered. Only an explicit {@code NONE} is read as "nothing here answers this".
 */
public class LlmContextGrader implements ContextGrader {

    private static final Logger log = LoggerFactory.getLogger("CONTEXT_GRADE");

    private static final String INSTRUCTION =
            "Return the numbers of the passages that could answer the question, comma-separated. "
                    + "If none of them could, reply NONE.";

    private final ChatClient chatClient;
    private final int maxExcerptChars;

    public LlmContextGrader(ChatClient chatClient, int maxExcerptChars) {
        this.chatClient = chatClient;
        this.maxExcerptChars = Math.max(40, maxExcerptChars);
    }

    @Override
    public List<Document> relevant(String question, List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            String prompt = PassageRanking.prompt(question, candidates, maxExcerptChars, INSTRUCTION);
            String reply = chatClient.prompt().user(prompt).call().content();
            if (PassageRanking.saysNone(reply)) {
                return List.of();
            }
            List<Integer> keep = PassageRanking.parse(reply, candidates.size());
            if (keep.isEmpty()) {
                // Unparseable, not "none": treat everything as relevant so the gate does not refuse.
                return candidates;
            }
            return keep.stream().map(index -> candidates.get(index - 1)).toList();
        } catch (RuntimeException ex) {
            log.warn("[CONTEXT-GRADE] grading skipped, treating all candidates as relevant: {}", ex.getMessage());
            return candidates;
        }
    }
}
