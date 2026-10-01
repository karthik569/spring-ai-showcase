package com.example.springai.config;

import com.example.springai.advisor.TokenUsageAdvisor;
import com.example.springai.cache.SemanticCacheAdvisor;
import com.example.springai.guardrail.PiiRedactor;
import com.example.springai.memory.SummarizingChatMemory;
import com.example.springai.observability.AiMetrics;
import com.example.springai.rag.ContextGrader;
import com.example.springai.rag.LlmContextGrader;
import com.example.springai.rag.LlmReranker;
import com.example.springai.rag.RagCoverageGate;
import com.example.springai.rag.Reranker;
import com.example.springai.vectorstore.HybridVectorStore;
import com.example.springai.vectorstore.KeywordIndex;
import com.example.springai.vectorstore.RerankingVectorStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class AiConfig {

    private static final String SYSTEM_PROMPT =
            "You are an expert AI assistant powered by Spring AI. You provide clear, accurate, and structured responses.";

    // The window used when summarization is off. It is small because the local model's context is: an
    // unbounded history pushes the actual question out of the prompt. When summarization is on the window
    // becomes an upper bound only, and the summarizer — not the trim — is what keeps the prompt short.
    private static final int MEMORY_WINDOW_MESSAGES = 6;

    /**
     * The repository is auto-configured (JDBC backed by H2) and owns the schema. With summarization on, the
     * window is widened and wrapped in a {@link SummarizingChatMemory} that folds the oldest turns into one
     * summary message once the stored history passes the trigger, so a long conversation keeps its thread
     * instead of dropping its opening. {@code MessageChatMemoryAdvisor} is untouched: it still consumes a
     * plain {@link ChatMemory}.
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                                 @Qualifier("conversationSummarizer") ChatClient conversationSummarizer,
                                 PiiRedactor piiRedactor,
                                 @Value("${app.memory.summarization.enabled:true}") boolean summarize,
                                 @Value("${app.memory.summarization.trigger-messages:8}") int triggerMessages,
                                 @Value("${app.memory.summarization.keep-recent-messages:4}") int keepRecent,
                                 @Value("${app.memory.summarization.max-window-messages:30}") int maxWindow,
                                 @Value("${app.memory.summarization.max-summary-chars:400}") int maxSummaryChars) {
        ChatMemory window = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(summarize ? Math.max(maxWindow, triggerMessages + 1) : MEMORY_WINDOW_MESSAGES)
                .build();
        if (!summarize) {
            return window;
        }
        return new SummarizingChatMemory(window, conversationSummarizer, piiRedactor,
                triggerMessages, keepRecent, maxSummaryChars);
    }

    /**
     * Folds older turns into one summary message for {@link #chatMemory} when the window overflows.
     *
     * <p>A separate bean for the same reason as {@link #ragQueryRewriter}: {@code defaultAdvisors} accumulates,
     * so a client built from a builder that already carries the memory advisor would recurse through the very
     * memory it is compacting. Temperature 0 and a small output cap because this is compression, not
     * composition.
     */
    @Bean
    @Qualifier("conversationSummarizer")
    public ChatClient conversationSummarizer(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You summarize an earlier conversation in a few sentences. Keep names, numbers,"
                        + " decisions and any preferences the user stated. Answer with the summary only.")
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.0).maxTokens(256).build())
                .build();
    }

    /**
     * Applied to every ChatClient.Builder the container hands out, so the clients built inside controllers
     * (RAG) report usage too instead of each one re-declaring the advisor.
     */
    @Bean
    public ChatClientCustomizer tokenUsageAdvisorCustomizer(AiMetrics aiMetrics) {
        return builder -> builder.defaultAdvisors(new TokenUsageAdvisor(aiMetrics));
    }

    /**
     * Stateless client for the one-shot endpoints (tools, structured output, vision, RAG). Memory is
     * deliberately absent: those calls have no conversation, and a shared conversation would make every
     * earlier request part of the next prompt. It also cannot carry the memory advisor at all, because
     * since Spring AI 1.1.6 the advisor rejects a call that supplies no conversation id.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, SemanticCacheAdvisor semanticCacheAdvisor) {
        return builder.defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(semanticCacheAdvisor)
                .build();
    }

    /**
     * Replays an answer to a semantically identical question instead of generating it again.
     *
     * <p>Attached to {@code chatClient} alone. The memory client's answer depends on history the cache key
     * deliberately ignores, the rewriter must not be handed a stale standalone question, and a RAG answer
     * goes stale the moment a document is uploaded — so none of those are cached. Within this client, a
     * request that carries tools, an output schema, an image or a conversation id also falls straight
     * through; only a plain text question is cached.
     */
    @Bean
    public SemanticCacheAdvisor semanticCacheAdvisor(
            EmbeddingModel embeddingModel,
            AiMetrics aiMetrics,
            @Value("${app.cache.enabled:true}") boolean enabled,
            @Value("${app.cache.similarity-threshold:0.95}") double similarityThreshold,
            @Value("${app.cache.max-entries:200}") int maxEntries,
            @Value("${app.cache.ttl:PT30M}") Duration ttl) {
        return new SemanticCacheAdvisor(embeddingModel, enabled, similarityThreshold, maxEntries, ttl, aiMetrics);
    }

    @Bean
    @Qualifier("conversationalChatClient")
    public ChatClient conversationalChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * Rewrites a follow-up into a standalone question, so retrieval gets the antecedent instead of the pronoun.
     *
     * <p>A separate bean rather than a second client built inside {@code RagController}: {@code ChatClient.Builder}
     * is prototype-scoped but {@code defaultAdvisors} <em>accumulates</em>, so a client built from the same builder
     * the RAG controller already used would inherit {@code QuestionAnswerAdvisor} and answer the rewrite prompt
     * with retrieved policy text. Here the only advisor is the one every builder gets from
     * {@link #tokenUsageAdvisorCustomizer()}, which is what makes the second model call visible in
     * {@code TOKEN_USAGE}.
     *
     * <p>Temperature 0 and 64 tokens because this is a transformation, not a composition — the measured failure
     * mode of {@code qwen2.5:0.5b-instruct} on this prompt is an over-long creative answer, not a wrong one.
     */
    @Bean
    @Qualifier("ragQueryRewriter")
    public ChatClient ragQueryRewriter(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You rewrite one question. Answer with the rewritten question only.")
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.0).maxTokens(64).build())
                .build();
    }

    /**
     * The vectorizer is a dedicated model on its own endpoint, not the chat model. A chat model used for
     * embeddings still returns vectors, so the misconfiguration is silent — it just ranks near-randomly.
     * Pointing this at a separate {@code llama-server} is what lets chat and embedding models differ, which
     * the single {@code spring.ai.openai.base-url} cannot express.
     *
     * <p>{@code @Primary} because the auto-configured {@code OpenAiEmbeddingModel} still exists and also
     * implements {@link EmbeddingModel}; without it the {@link SimpleVectorStore} injection would be ambiguous.
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel(
            @Value("${app.embedding.base-url:http://localhost:8082}") String baseUrl,
            @Value("${app.embedding.model:all-minilm}") String model,
            @Value("${OPENAI_API_KEY:demo-api-key}") String apiKey) {

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    /**
     * The subtype is exposed, not {@code VectorStore}, because persistence lives on it: RagStorePersistence
     * calls save/load, and every injection point still asks for the interface.
     */
    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * The store that fuses a BM25 keyword ranking into the vector ranking, so an acronym a cosine model
     * cannot represent is still recovered by its literal term.
     *
     * <p>Exposed as the concrete type, not the primary {@code VectorStore}: callers that need {@code
     * rebuildKeywordIndex} (the boot ingestion runner, through {@link
     * com.example.springai.vectorstore.KeywordLegRebuildable}) and the CRAG coverage gate, which wants the
     * fused ranking without the re-ranker's extra model call, both ask for it directly.
     */
    @Bean
    public HybridVectorStore hybridVectorStore(SimpleVectorStore vectorStore,
                                               KeywordIndex keywordIndex,
                                               @Value("${app.rag.hybrid.enabled:true}") boolean enabled,
                                               @Value("${app.rag.hybrid.candidate-pool:50}") int candidatePool,
                                               @Value("${app.rag.hybrid.rrf-k:60}") int rrfK) {
        return new HybridVectorStore(vectorStore, keywordIndex, enabled, candidatePool, rrfK);
    }

    /**
     * The {@code VectorStore} every retriever actually receives: the hybrid store with an optional LLM
     * re-rank pass over its fused result. {@code @Primary} so the QA advisor, {@code /rag/search} and the
     * knowledge-base tool all get it without naming it. Off by default — see {@code app.rag.rerank.enabled}.
     */
    @Bean
    @Primary
    public VectorStore rerankingVectorStore(HybridVectorStore hybridVectorStore,
                                            Reranker reranker,
                                            @Value("${app.rag.rerank.enabled:false}") boolean enabled,
                                            @Value("${app.rag.rerank.candidates:12}") int candidates) {
        return new RerankingVectorStore(hybridVectorStore, reranker, enabled, candidates);
    }

    /**
     * Orders a fused result list by relevance. A separate bean so it is the shipped {@link LlmReranker}
     * behind the interface, and the retrieval tests can substitute a deterministic one.
     */
    @Bean
    public Reranker reranker(@Qualifier("ragReranker") ChatClient ragReranker,
                             @Value("${app.rag.rerank.max-excerpt-chars:200}") int maxExcerptChars) {
        return new LlmReranker(ragReranker, maxExcerptChars);
    }

    /**
     * The re-ranker's client. Single-digit output and temperature 0 because this is a ranking, not a
     * composition; a separate bean so the default advisors do not accumulate onto the RAG builder.
     */
    @Bean
    @Qualifier("ragReranker")
    public ChatClient ragReranker(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You rank passages by how well they answer a question. Answer with numbers only.")
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.0).maxTokens(32).build())
                .build();
    }

    /**
     * Judges whether retrieved chunks can answer the question. Separate from {@link #reranker} because it
     * may reply "none", which the re-ranker may not.
     */
    @Bean
    public ContextGrader contextGrader(@Qualifier("ragGrader") ChatClient ragGrader,
                                       @Value("${app.rag.crag.max-excerpt-chars:200}") int maxExcerptChars) {
        return new LlmContextGrader(ragGrader, maxExcerptChars);
    }

    @Bean
    @Qualifier("ragGrader")
    public ChatClient ragGrader(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You decide which passages could answer a question. Answer with numbers only, or NONE.")
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.0).maxTokens(32).build())
                .build();
    }

    /**
     * The pre-answer coverage gate for the RAG path. Built over the concrete {@link HybridVectorStore} so its
     * retrieval reflects fusion and does not pay the re-ranker's model call.
     */
    @Bean
    public RagCoverageGate ragCoverageGate(HybridVectorStore hybridVectorStore,
                                           ContextGrader contextGrader,
                                           @Value("${app.rag.crag.enabled:true}") boolean enabled,
                                           @Value("${app.rag.crag.candidates:6}") int candidates,
                                           @Value("${app.rag.crag.min-relevant:1}") int minRelevant) {
        return new RagCoverageGate(hybridVectorStore, contextGrader, enabled, candidates, minRelevant);
    }
}
