package com.example.springai.cache;

import com.example.springai.observability.AiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers a question that is semantically identical to one already answered, without calling the model.
 *
 * <p>On this hardware a generation is the whole cost of a request, so replaying the answer to "how many
 * annual leave days do I get?" when the previous question was "how much annual leave do I get?" turns a
 * multi-second call into an embedding plus a cosine. The lookup is a vector search over prior questions, so
 * it catches a paraphrase, not just a repeated string.
 *
 * <p>It is a {@link CallAdvisor}, which is what lets a hit <em>short-circuit</em>: returning without calling
 * {@code chain.nextCall} skips the memory advisor, the model, and {@code TokenUsageAdvisor} alike. That is
 * also why it must be the outermost advisor.
 *
 * <p>The store is its own {@link SimpleVectorStore}, not the knowledge base: a cached answer is keyed by a
 * question and must never surface as a retrieved document in a RAG citation.
 *
 * <p>Several requests are deliberately not cached and fall straight through — see {@link #cacheableKeyOf}.
 */
public class SemanticCacheAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger("SEMANTIC_CACHE");

    static final String ANSWER_METADATA = "answer";
    private static final String CREATED_AT_METADATA = "createdAt";

    private final VectorStore cacheStore;
    private final boolean enabled;
    private final double similarityThreshold;
    private final int maxEntries;
    private final Duration ttl;
    private final AiMetrics aiMetrics;

    // Insertion order, oldest first, so eviction is FIFO. Guarded by synchronizing on the list itself.
    private final LinkedList<CachedEntry> entries = new LinkedList<>();

    public SemanticCacheAdvisor(EmbeddingModel embeddingModel,
                                boolean enabled,
                                double similarityThreshold,
                                int maxEntries,
                                Duration ttl,
                                AiMetrics aiMetrics) {
        this.cacheStore = SimpleVectorStore.builder(embeddingModel).build();
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.maxEntries = Math.max(1, maxEntries);
        this.ttl = ttl;
        this.aiMetrics = aiMetrics;
    }

    @Override
    public String getName() {
        return "SemanticCacheAdvisor";
    }

    @Override
    public int getOrder() {
        // Outermost, ahead of the terminal advisor. A hit has to short-circuit before anything else runs, and
        // a miss has to wrap the whole chain so the answer it observes is the one that gets stored.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String key = cacheableKeyOf(request);
        if (!enabled || key == null) {
            return chain.nextCall(request);
        }

        String cached = lookup(key);
        if (cached != null) {
            aiMetrics.recordCache(true);
            log.info("[SEMANTIC-CACHE] replaying an answer for a {}-character question", key.length());
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(cached)))))
                    .context(request.context())
                    .build();
        }

        aiMetrics.recordCache(false);
        ChatClientResponse response = chain.nextCall(request);
        String answer = textOf(response);
        if (answer != null && !answer.isBlank()) {
            store(key, answer);
        }
        return response;
    }

    /**
     * The text to key this request on, or {@code null} when it must not be cached.
     *
     * <p>Anything whose answer depends on more than the prompt text is excluded rather than cached wrongly:
     * a tool call has side effects and fresh data (the weather), a conversation's answer depends on history
     * the key ignores, an image is not in the text at all, and a structured output has a schema the cached
     * plain text cannot satisfy.
     */
    private String cacheableKeyOf(ChatClientRequest request) {
        if (hasConversation(request) || hasTools(request) || hasOutputSchema(request) || hasMedia(request)) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (Message message : request.prompt().getInstructions()) {
            if (message.getText() != null) {
                key.append(message.getMessageType()).append(':').append(message.getText()).append('\n');
            }
        }
        return key.length() == 0 ? null : key.toString();
    }

    private static boolean hasConversation(ChatClientRequest request) {
        Object id = request.context().get(ChatMemory.CONVERSATION_ID);
        return id instanceof String value && !value.isBlank();
    }

    // OpenAiChatOptions is always an instance of ToolCallingChatOptions, so the test is on the value, not the
    // type — a plain chat request carries an empty tool set and must still be cacheable.
    private static boolean hasTools(ChatClientRequest request) {
        ChatOptions options = request.prompt().getOptions();
        if (!(options instanceof ToolCallingChatOptions tools)) {
            return false;
        }
        return (tools.getToolNames() != null && !tools.getToolNames().isEmpty())
                || (tools.getToolCallbacks() != null && !tools.getToolCallbacks().isEmpty());
    }

    // OpenAiChatOptions.getOutputSchema() is not null-safe — it dereferences the response format, which is
    // absent on a plain chat request — so the concrete type is checked on its own terms first.
    private static boolean hasOutputSchema(ChatClientRequest request) {
        ChatOptions options = request.prompt().getOptions();
        if (options instanceof OpenAiChatOptions openai) {
            return openai.getResponseFormat() != null;
        }
        return options instanceof StructuredOutputChatOptions structured && structured.getOutputSchema() != null;
    }

    private static boolean hasMedia(ChatClientRequest request) {
        if (request.prompt().getUserMessage() == null) {
            return false;
        }
        List<?> media = request.prompt().getUserMessage().getMedia();
        return media != null && !media.isEmpty();
    }

    private String lookup(String key) {
        purgeExpired();
        List<Document> hits = cacheStore.similaritySearch(SearchRequest.builder()
                .query(key)
                .topK(1)
                .similarityThresholdAll()
                .build());
        if (hits.isEmpty()) {
            return null;
        }
        Document best = hits.get(0);
        Double score = best.getScore();
        if (score == null || score < similarityThreshold) {
            return null;
        }
        Object answer = best.getMetadata().get(ANSWER_METADATA);
        return answer instanceof String text ? text : null;
    }

    private void store(String key, String answer) {
        purgeExpired();
        String id = UUID.randomUUID().toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ANSWER_METADATA, answer);
        metadata.put(CREATED_AT_METADATA, Instant.now().toString());
        Document entry = Document.builder().id(id).text(key).metadata(metadata).build();
        // SimpleVectorStore embeds an added Document through getFormattedContent(MetadataMode.EMBED), which
        // folds the metadata into the vector, while a search embeds the bare query string. Left as-is, a
        // stored question is embedded alongside its answer and timestamp and can never match itself, because
        // the lookup side carries neither. Formatting to the text alone puts both sides on the same footing.
        entry.setContentFormatter((document, mode) -> document.getText());
        cacheStore.add(List.of(entry));

        synchronized (entries) {
            entries.addLast(new CachedEntry(id, Instant.now()));
            while (entries.size() > maxEntries) {
                cacheStore.delete(List.of(entries.removeFirst().id()));
            }
        }
    }

    private void purgeExpired() {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        Instant cutoff = Instant.now().minus(ttl);
        synchronized (entries) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                CachedEntry entry = iterator.next();
                if (entry.createdAt().isBefore(cutoff)) {
                    cacheStore.delete(List.of(entry.id()));
                    iterator.remove();
                }
            }
        }
    }

    private static String textOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }

    private record CachedEntry(String id, Instant createdAt) {
    }
}
