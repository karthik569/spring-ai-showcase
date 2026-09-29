package com.example.springai.controller;

import com.example.springai.dto.RagQueryRequest;
import com.example.springai.dto.RagQueryResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/rag")
public class RagController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final int defaultTopK;

    public RagController(ChatClient.Builder chatClientBuilder,
                         VectorStore vectorStore,
                         @Value("${app.rag.top-k:4}") int defaultTopK,
                         @Value("${app.rag.similarity-threshold:0.5}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.defaultTopK = defaultTopK;
        this.chatClient = chatClientBuilder
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore,
                        SearchRequest.query("").withTopK(defaultTopK).withSimilarityThreshold(similarityThreshold)))
                .build();
    }

    @PostMapping("/query")
    public RagQueryResponse queryKnowledgeBase(@RequestBody RagQueryRequest request) {
        long start = System.currentTimeMillis();

        // 1. Perform similarity search directly to extract source snippets for provenance
        List<Document> similarDocuments = vectorStore.similaritySearch(
                SearchRequest.query(request.question())
                        .withTopK(request.topK() > 0 ? request.topK() : defaultTopK)
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

    // An inspection endpoint: an empty list must not look like an empty store, so the reason for zero
    // matches is reported. M4's SimpleVectorStore drops the similarity score from the returned documents.
    @GetMapping("/search")
    public Map<String, Object> rawVectorSearch(
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "0") int topK) {

        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(topK > 0 ? topK : defaultTopK)
                        .withSimilarityThresholdAll()
        );

        List<Map<String, Object>> results = documents.stream()
                .map(doc -> Map.of(
                        "id", doc.getId(),
                        "content", doc.getContent() != null ? doc.getContent() : "",
                        "metadata", (Object) doc.getMetadata()
                ))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("totalResults", results.size());
        response.put("results", results);
        if (results.isEmpty()) {
            response.put("note", "No stored chunk scored above 0.0 cosine for this query;"
                    + " the search floor cannot be lowered further because negative similarity is always discarded.");
        }
        return response;
    }
}
