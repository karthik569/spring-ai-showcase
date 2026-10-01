package com.example.springai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;

/**
 * Decides whether the knowledge base can answer a question before the model is asked to.
 *
 * <p>This is the "correct" step of a self-correcting RAG, made as narrow as possible. The existing answer
 * path is left exactly as it was when the gate says yes, so every measured sample still holds; the gate only
 * changes what happens in the case the old path got wrong — a question the knowledge base does not cover,
 * which used to be answered from the model's own memory.
 *
 * <p>The gate is a coverage judgement, not a score floor: SESSION-HANDOFF records that an unanswerable
 * question scored 0.405 here, above three genuine matches, so a cosine cutoff would refuse answerable
 * questions and accept unanswerable ones. When nothing is relevant, it broadens once — dropping the
 * follow-up rewrite and the filename filter — and only then refuses. The retrieved candidates are returned
 * either way, so a refusal can quote the closest passage's score whether or not it passed anything.
 *
 * <p>It holds a plain {@link VectorStore} so the caller can hand it the fusion layer directly: the coverage
 * judgement should reflect real retrieval, not a downstream re-ranker's opinion of it.
 */
public class RagCoverageGate {

    public enum Outcome {
        /** The gate is off; the answer path runs unchanged. */
        DISABLED,
        /** The retrieved candidates cover the question. */
        GROUNDED,
        /** Nothing covered the original query, but the broadened retry found context. */
        RE_QUERIED,
        /** Neither the query nor the broadened retry found anything relevant; do not answer. */
        REFUSED
    }

    public record Decision(boolean answerable, String query, Outcome outcome,
                           List<Document> candidates, List<Document> relevant) {
    }

    private final VectorStore vectorStore;
    private final ContextGrader grader;
    private final boolean enabled;
    private final int candidateLimit;
    private final int minRelevant;

    public RagCoverageGate(VectorStore vectorStore, ContextGrader grader,
                           boolean enabled, int candidateLimit, int minRelevant) {
        this.vectorStore = vectorStore;
        this.grader = grader;
        this.enabled = enabled;
        this.candidateLimit = Math.max(1, candidateLimit);
        this.minRelevant = Math.max(1, minRelevant);
    }

    /**
     * @param resolvedQuery the text the resolver would search (equals {@code rawQuestion} when no rewrite)
     * @param rawQuestion   the caller's own text, used for the broadened retry
     * @param filename      an already-validated filename filter, or {@code null} for none
     */
    public Decision assess(String resolvedQuery, String rawQuestion, String filename) {
        if (!enabled) {
            return new Decision(true, resolvedQuery, Outcome.DISABLED, List.of(), List.of());
        }

        List<Document> first = retrieve(resolvedQuery, filename);
        List<Document> relevantFirst = grader.relevant(resolvedQuery, first);
        if (relevantFirst.size() >= minRelevant) {
            return new Decision(true, resolvedQuery, Outcome.GROUNDED, first, relevantFirst);
        }

        String broadened = hasText(rawQuestion) ? rawQuestion : resolvedQuery;
        boolean differs = !broadened.equals(resolvedQuery) || hasText(filename);
        if (differs) {
            List<Document> second = retrieve(broadened, null);
            List<Document> relevantSecond = grader.relevant(broadened, second);
            if (relevantSecond.size() >= minRelevant) {
                return new Decision(true, broadened, Outcome.RE_QUERIED, second, relevantSecond);
            }
            return new Decision(false, broadened, Outcome.REFUSED, second, relevantSecond);
        }
        return new Decision(false, resolvedQuery, Outcome.REFUSED, first, relevantFirst);
    }

    private List<Document> retrieve(String query, String filename) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(candidateLimit)
                .similarityThresholdAll();
        if (hasText(filename)) {
            builder.filterExpression(new FilterExpressionBuilder().eq("filename", filename).build());
        }
        return vectorStore.similaritySearch(builder.build());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
