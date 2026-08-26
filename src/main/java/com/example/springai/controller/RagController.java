package com.example.springai.controller;

import com.example.springai.dto.RagQueryRequest;
import com.example.springai.dto.RagQueryResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/rag")
public class RagController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore, SearchRequest.query("").withTopK(4).withSimilarityThreshold(0.5)))
                .build();
    }

    @PostMapping("/query")
    public RagQueryResponse queryKnowledgeBase(@RequestBody RagQueryRequest request) {
        long start = System.currentTimeMillis();

        // 1. Perform similarity search directly to extract source snippets for provenance
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.query(request.question())
                        .withTopK(request.topK() > 0 ? request.topK() : 3)
        );

        List<String> sourceDocs = similarDocuments.stream()
                .map(doc -> {
                    String filename = String.valueOf(doc.getMetadata().getOrDefault("filename", "unknown"));
                    String content = doc.getContent() != null ? doc.getContent() : "";
                    return filename + ": " + content.substring(0, Math.min(content.length(), 120)) + "...";
                })
                .toList();

        // 2. Generate grounded answer via QuestionAnswerAdvisor augmented ChatClient
        String answer = chatClient.prompt()
                .user(request.question())
                .call()
                .content();

        long duration = System.currentTimeMillis() - start;
        return new RagQueryResponse(request.question(), answer != null ? answer : "", sourceDocs, duration);
    }

    @GetMapping("/search")
    public Map<String, Object> rawVectorSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "3") int topK) {

        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(topK)
        );

        List<Map<String, Object>> results = documents.stream()
                .map(doc -> Map.of(
                        "id", doc.getId(),
                        "content", doc.getContent() != null ? doc.getContent() : "",
                        "metadata", (Object) doc.getMetadata()
                ))
                .toList();

        return Map.of("query", query, "totalResults", results.size(), "results", results);
    }
}
