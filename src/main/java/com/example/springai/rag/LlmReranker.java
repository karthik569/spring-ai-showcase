package com.example.springai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * A re-ranker that asks the model to order the candidates in one call.
 *
 * <p>Listwise, not pointwise: scoring N passages with N separate calls would cost N generations on a device
 * where one generation is the whole request, so the passages are numbered and the model returns the order
 * in a single short reply. It runs after fusion, so it sees the fused ranking and only moves what the
 * cosine-and-BM25 ranking already agreed was plausible.
 *
 * <p>It never changes membership — every document returned is one it was handed — and every failure keeps
 * the incoming order. A re-ranker that can drop a passage would be a filter wearing a ranking's name, and
 * a noisy 1.5B model is not trusted with that.
 */
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger("RERANK");

    private static final String INSTRUCTION =
            "Return the numbers of the passages most relevant to the question, most relevant first, "
                    + "comma-separated. Only the numbers.";

    private final ChatClient chatClient;
    private final int maxExcerptChars;

    public LlmReranker(ChatClient chatClient, int maxExcerptChars) {
        this.chatClient = chatClient;
        this.maxExcerptChars = Math.max(40, maxExcerptChars);
    }

    @Override
    public List<Document> rerank(String query, List<Document> documents) {
        if (query == null || query.isBlank() || documents == null || documents.size() <= 1) {
            return documents == null ? List.of() : documents;
        }
        try {
            String prompt = PassageRanking.prompt(query, documents, maxExcerptChars, INSTRUCTION);
            String reply = chatClient.prompt().user(prompt).call().content();
            List<Integer> order = PassageRanking.parse(reply, documents.size());
            if (order.isEmpty()) {
                return documents;
            }
            return reorder(documents, order);
        } catch (RuntimeException ex) {
            log.warn("[RERANK] re-rank skipped, keeping the fused order: {}", ex.getMessage());
            return documents;
        }
    }

    private static List<Document> reorder(List<Document> documents, List<Integer> order) {
        List<Document> ranked = new ArrayList<>(documents.size());
        for (int index : order) {
            ranked.add(documents.get(index - 1));
        }
        // A passage the model did not name keeps its fused position, after everything it did name.
        for (int i = 0; i < documents.size(); i++) {
            if (!order.contains(i + 1)) {
                ranked.add(documents.get(i));
            }
        }
        return ranked;
    }
}
