package com.example.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping
    public Map<String, String> simpleChat(@RequestParam(defaultValue = "Explain the advantages of Spring Boot 3 in 2 sentences") String message) {
        String response = chatClient.prompt()
                .user(message)
                .call()
                .content();
        return Map.of("prompt", message, "response", response != null ? response : "");
    }

    @GetMapping("/template")
    public Map<String, Object> templateChat(
            @RequestParam(defaultValue = "Software Engineer") String role,
            @RequestParam(defaultValue = "Explain Dependency Injection") String topic) {

        String response = chatClient.prompt()
                .system(s -> s.text("You are a senior tech mentor explaining concepts to a {role}.").param("role", role))
                .user(u -> u.text("Topic to explain: {topic}. Give a crisp, beginner-friendly analogy.").param("topic", topic))
                .call()
                .content();

        return Map.of("role", role, "topic", topic, "explanation", response != null ? response : "");
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestParam(defaultValue = "Write a haiku about Java programming") String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }

    @GetMapping("/memory")
    public Map<String, String> chatWithMemory(
            @RequestParam String message,
            @RequestParam(defaultValue = "session-123") String conversationId) {

        String response = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .call()
                .content();

        return Map.of(
                "conversationId", conversationId,
                "message", message,
                "response", response != null ? response : ""
        );
    }
}
