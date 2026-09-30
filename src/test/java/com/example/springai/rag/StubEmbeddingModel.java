package com.example.springai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic, offline stand-in for {@code all-minilm}, so {@code SimpleVectorStore}'s real cosine,
 * threshold, filter and ranking code can be asserted without a running Ollama.
 *
 * <p>Vectors are term hashes: a chunk's similarity to a query is shared-word overlap, which is far sharper
 * than a real embedding and collides on rare words. That is why the harness gates on <em>rank</em> against
 * thresholds calibrated by running this stub, and why {@link GoldenQuestionsTest} makes no claim about
 * production scores — those are tier 2's job.
 */
public class StubEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 512;
    private static final int MIN_TERM_LENGTH = 2;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String term : terms(text)) {
            vector[Math.floorMod(term.hashCode(), DIMENSIONS)] += 1.0f;
        }
        return normalize(vector);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    /**
     * {@code SimpleVectorStore} only ever asks for one text at a time; anything reaching this method means
     * a call site that the stub was not written for, which should fail loudly rather than return zeros.
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        throw new UnsupportedOperationException("StubEmbeddingModel serves embed(...) calls only");
    }

    private static String[] terms(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        return text.toLowerCase().split("[^a-z0-9]+");
    }

    private static float[] normalize(float[] vector) {
        double magnitude = 0;
        for (float value : vector) {
            magnitude += value * value;
        }
        if (magnitude == 0) {
            return vector;
        }
        double scale = Math.sqrt(magnitude);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / scale);
        }
        return vector;
    }
}
