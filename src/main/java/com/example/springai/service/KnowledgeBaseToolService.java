package com.example.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * RAG exposed as a tool: instead of the advisor always retrieving, the model itself decides a question
 * needs the knowledge base. Used by the agentic endpoint in {@code ToolCallingController}; the grounded
 * {@code /api/ai/rag/query} path keeps its advisor.
 */
@Configuration
public class KnowledgeBaseToolService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseToolService.class);

    /**
     * @param query the standalone question to look up in the knowledge base
     */
    public record Request(String query) {}

    public record Hit(String filename, double score, String excerpt) {}

    private final VectorStore vectorStore;
    private final int topK;

    public KnowledgeBaseToolService(VectorStore vectorStore, @Value("${app.rag.top-k:4}") int topK) {
        this.vectorStore = vectorStore;
        this.topK = topK;
    }

    @Bean
    @Description("Search the company knowledge base for policy and document facts; call before answering questions about company rules, documents or runbooks")
    public Function<Request, List<Hit>> searchKnowledgeBase() {
        return request -> {
            log.info("[SPRING-AI-TOOL] Executing searchKnowledgeBase tool call for query={}", request.query());
            // Zero floor, same as /rag/search: ranking is the point, and a threshold tuned for the grounded
            // endpoint would silently empty the tool's answer.
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(request.query())
                    .topK(topK)
                    .similarityThresholdAll()
                    .build());
            return documents.stream()
                    .map(doc -> new Hit(
                            String.valueOf(doc.getMetadata().getOrDefault("filename", "unknown")),
                            doc.getScore(),
                            doc.getText() != null ? doc.getText() : ""))
                    .toList();
        };
    }
}
