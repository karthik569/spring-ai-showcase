package com.example.springai.cache;

import com.example.springai.observability.AiMetrics;
import com.example.springai.rag.StubEmbeddingModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache's contract, asserted against a counting chain rather than a model: a hit must reach the chain
 * zero times, and every bypass must reach it every time.
 *
 * <p>The stub embedder makes the lookup deterministic — vectors here are shared-word overlap — so a hit or a
 * miss is a property of the text, not of {@code all-minilm}'s mood. It is used through
 * {@link MetadataInclusiveEmbeddingModel}, which reproduces {@code OpenAiEmbeddingModel}'s habit of folding a
 * Document's metadata into its vector; without that fidelity a stored question would appear to match itself
 * even when it does not.
 */
class SemanticCacheAdvisorTest {

    private static final String QUESTION = "How many days of annual leave do I get?";
    private static final String ANSWER = "25 days.";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AiMetrics aiMetrics = new AiMetrics(registry);

    @Test
    void servesARepeatedQuestionWithoutCallingTheModelTwice() {
        CountingChain chain = new CountingChain(ANSWER);
        SemanticCacheAdvisor advisor = advisor(true);

        ChatClientResponse first = advisor.adviseCall(request(QUESTION, null, Map.of()), chain);
        ChatClientResponse second = advisor.adviseCall(request(QUESTION, null, Map.of()), chain);

        assertEquals(1, chain.calls, "a repeated question should reach the model once");
        assertEquals(ANSWER, textOf(first));
        assertEquals(ANSWER, textOf(second), "the second answer must come from the cache");
        assertEquals(1L, cache(aiMetrics).get("hits"));
        assertEquals(1L, cache(aiMetrics).get("misses"));
    }

    @Test
    void neverCachesARequestThatCarriesTools() {
        CountingChain chain = new CountingChain("It is 18 degrees.");
        SemanticCacheAdvisor advisor = advisor(true);
        var options = DefaultToolCallingChatOptions.builder().toolNames("getCurrentWeather").build();

        advisor.adviseCall(request("What is the weather in Tokyo?", options, Map.of()), chain);
        advisor.adviseCall(request("What is the weather in Tokyo?", options, Map.of()), chain);

        assertEquals(2, chain.calls, "a tool request must always reach the model");
        assertEquals(0L, cache(aiMetrics).get("hits"));
    }

    @Test
    void neverCachesATurnThatBelongsToAConversation() {
        CountingChain chain = new CountingChain(ANSWER);
        SemanticCacheAdvisor advisor = advisor(true);

        advisor.adviseCall(request(QUESTION, null, Map.of(ChatMemory.CONVERSATION_ID, "conv-1")), chain);
        advisor.adviseCall(request(QUESTION, null, Map.of(ChatMemory.CONVERSATION_ID, "conv-1")), chain);

        assertEquals(2, chain.calls, "a conversational turn depends on history the key ignores");
        assertEquals(0L, cache(aiMetrics).get("hits"));
    }

    @Test
    void servesNothingWhenDisabled() {
        CountingChain chain = new CountingChain(ANSWER);
        SemanticCacheAdvisor advisor = advisor(false);

        advisor.adviseCall(request(QUESTION, null, Map.of()), chain);
        advisor.adviseCall(request(QUESTION, null, Map.of()), chain);

        assertEquals(2, chain.calls);
        assertEquals(0L, cache(aiMetrics).get("hits"));
        assertEquals(0L, cache(aiMetrics).get("misses"));
    }

    // OpenAiChatOptions.getOutputSchema() dereferences a response format that a plain chat does not carry,
    // so the bypass check must not call it blindly; a plain OpenAI request is cacheable and must not throw.
    @Test
    void cachesAPlainOpenAiRequestInsteadOfThrowing() {
        CountingChain chain = new CountingChain(ANSWER);
        SemanticCacheAdvisor advisor = advisor(true);
        var options = OpenAiChatOptions.builder().build();

        advisor.adviseCall(request(QUESTION, options, Map.of()), chain);
        advisor.adviseCall(request(QUESTION, options, Map.of()), chain);

        assertEquals(1, chain.calls, "a plain OpenAI chat request should be cached");
        assertEquals(1L, cache(aiMetrics).get("hits"));
    }

    @Test
    void neverCachesARequestConstrainedByAnOutputSchema() {
        CountingChain chain = new CountingChain("{}");
        SemanticCacheAdvisor advisor = advisor(true);
        var options = OpenAiChatOptions.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema("{\"type\":\"object\"}")
                        .build())
                .build();

        advisor.adviseCall(request(QUESTION, options, Map.of()), chain);
        advisor.adviseCall(request(QUESTION, options, Map.of()), chain);

        assertEquals(2, chain.calls, "a schema-constrained request must always reach the model");
        assertEquals(0L, cache(aiMetrics).get("hits"));
    }

    private SemanticCacheAdvisor advisor(boolean enabled) {
        return new SemanticCacheAdvisor(new MetadataInclusiveEmbeddingModel(), enabled, 0.9, 10, Duration.ofMinutes(30), aiMetrics);
    }

    private static ChatClientRequest request(String question, Object options, Map<String, Object> context) {
        Prompt prompt = options == null
                ? new Prompt(List.of(new UserMessage(question)))
                : new Prompt(List.of(new UserMessage(question)),
                        (org.springframework.ai.chat.prompt.ChatOptions) options);
        return ChatClientRequest.builder().prompt(prompt).context(context).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cache(AiMetrics metrics) {
        return (Map<String, Object>) metrics.summary().get("cache");
    }

    private static String textOf(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }

    /**
     * The real model embeds an added Document through {@code getFormattedContent(MetadataMode.EMBED)} — text
     * plus metadata — but a searched string verbatim. Modelling that asymmetry is the point: the cache must
     * strip metadata off its stored keys itself, or a repeated question would embed differently to its own
     * query string and miss. The {@code StubEmbeddingModel} this extends only handles the string path, which
     * is precisely why it never caught the bug.
     */
    private static final class MetadataInclusiveEmbeddingModel extends StubEmbeddingModel {

        @Override
        public float[] embed(Document document) {
            return super.embed(document.getFormattedContent(MetadataMode.EMBED));
        }
    }

    /** A chain that returns a fixed answer and counts how often it was asked to. */
    private static final class CountingChain implements CallAdvisorChain {

        private final String answer;
        private int calls;

        private CountingChain(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            calls++;
            ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
            return ChatClientResponse.builder().chatResponse(chatResponse).build();
        }

        @Override
        public List<CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(CallAdvisor advisor) {
            return this;
        }
    }
}
