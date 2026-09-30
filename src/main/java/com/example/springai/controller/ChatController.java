package com.example.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/chat")
public class ChatController {

    // The chat memory schema stores the id in VARCHAR(36); a longer value fails on insert, deep inside JDBC.
    private static final int MAX_CONVERSATION_ID_CHARS = 36;

    private final ChatClient chatClient;
    private final ChatClient conversationalChatClient;
    private final ChatMemory chatMemory;

    public ChatController(ChatClient chatClient,
                          @Qualifier("conversationalChatClient") ChatClient conversationalChatClient,
                          ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.conversationalChatClient = conversationalChatClient;
        this.chatMemory = chatMemory;
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
    public Flux<String> streamChat(
            @RequestParam(defaultValue = "Write a haiku about Java programming") String message,
            @RequestParam(defaultValue = "session-123") String conversationId) {

        String conversation = requireConversationId(conversationId);
        return conversationalChatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation))
                .stream()
                .content();
    }

    // The conversation id is mandatory: the memory advisor throws without it, and it is what keeps two
    // callers' histories apart.
    @GetMapping("/memory")
    public Map<String, String> chatWithMemory(
            @RequestParam String message,
            @RequestParam(defaultValue = "session-123") String conversationId) {

        String conversation = requireConversationId(conversationId);
        String response = conversationalChatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation))
                .call()
                .content();

        return Map.of(
                "conversationId", conversation,
                "message", message,
                "response", response != null ? response : ""
        );
    }

    /**
     * Reads the stored transcript back, so a caller can see what the advisor will inject into the next
     * turn instead of guessing from the model's answer.
     */
    @GetMapping("/memory/history")
    public Map<String, Object> conversationHistory(@RequestParam String conversationId) {
        List<Message> messages = chatMemory.get(requireConversationId(conversationId));

        List<Map<String, Object>> transcript = messages.stream()
                .map(message -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("role", message.getMessageType().name().toLowerCase());
                    row.put("content", message.getText() != null ? message.getText() : "");
                    return row;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("messageCount", transcript.size());
        response.put("messages", transcript);
        return response;
    }

    @DeleteMapping("/memory")
    public Map<String, Object> clearConversation(@RequestParam String conversationId) {
        String id = requireConversationId(conversationId);
        chatMemory.clear(id);
        return Map.of("conversationId", id, "cleared", true);
    }

    private static String requireConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId must not be blank");
        }
        if (conversationId.length() > MAX_CONVERSATION_ID_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "conversationId is limited to " + MAX_CONVERSATION_ID_CHARS + " characters");
        }
        return conversationId;
    }
}
