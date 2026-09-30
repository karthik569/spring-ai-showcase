package com.example.springai.config;

import com.example.springai.advisor.TokenUsageAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    private static final String SYSTEM_PROMPT =
            "You are an expert AI assistant powered by Spring AI. You provide clear, accurate, and structured responses.";

    // A window, not the whole transcript: the local model's context is small enough that an unbounded
    // history pushes the actual question out of the prompt.
    private static final int MEMORY_WINDOW_MESSAGES = 6;

    // The repository is auto-configured (JDBC backed by H2) and owns the schema; this only bounds the window.
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MEMORY_WINDOW_MESSAGES)
                .build();
    }

    /**
     * Applied to every ChatClient.Builder the container hands out, so the clients built inside controllers
     * (RAG) report usage too instead of each one re-declaring the advisor.
     */
    @Bean
    public ChatClientCustomizer tokenUsageAdvisorCustomizer() {
        return builder -> builder.defaultAdvisors(new TokenUsageAdvisor());
    }

    /**
     * Stateless client for the one-shot endpoints (tools, structured output, vision, RAG). Memory is
     * deliberately absent: those calls have no conversation, and a shared conversation would make every
     * earlier request part of the next prompt. It also cannot carry the memory advisor at all, because
     * since Spring AI 1.1.6 the advisor rejects a call that supplies no conversation id.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM_PROMPT).build();
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
     * The subtype is exposed, not {@code VectorStore}, because persistence lives on it: RagStorePersistence
     * calls save/load, and every injection point still asks for the interface.
     */
    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
