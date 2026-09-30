package com.example.springai.config;

import com.example.springai.advisor.TokenUsageAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
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

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
