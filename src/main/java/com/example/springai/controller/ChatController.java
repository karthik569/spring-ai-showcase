package com.example.springai.controller;

import com.example.springai.guardrail.PiiRedactor;
import com.example.springai.support.ConversationIds;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatClient conversationalChatClient;
    private final ChatMemory chatMemory;
    private final PiiRedactor piiRedactor;

    public ChatController(ChatClient chatClient,
                          @Qualifier("conversationalChatClient") ChatClient conversationalChatClient,
                          ChatMemory chatMemory,
                          PiiRedactor piiRedactor) {
        this.chatClient = chatClient;
        this.conversationalChatClient = conversationalChatClient;
        this.chatMemory = chatMemory;
        this.piiRedactor = piiRedactor;
    }

    @GetMapping
    public Map<String, String> simpleChat(@RequestParam(defaultValue = "Explain the advantages of Spring Boot 3 in 2 sentences") String message) {
        String response = chatClient.prompt()
                .user(message)
                .call()
                .content();
        return Map.of("prompt", message, "response", response != null ? piiRedactor.redact(response) : "");
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

        String conversation = ConversationIds.requireNonBlank(conversationId);
        return conversationalChatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation))
                .stream()
                .content()
                .map(piiRedactor::redact);
    }

    // The conversation id is mandatory: the memory advisor throws without it, and it is what keeps two
    // callers' histories apart.
    @GetMapping("/memory")
    public Map<String, String> chatWithMemory(
            @RequestParam String message,
            @RequestParam(defaultValue = "session-123") String conversationId) {

        String conversation = ConversationIds.requireNonBlank(conversationId);
        String response = conversationalChatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation))
                .call()
                .content();

        return Map.of(
                "conversationId", conversation,
                "message", message,
                "response", response != null ? piiRedactor.redact(response) : ""
        );
    }

    /**
     * Reads the stored transcript back, so a caller can see what the advisor will inject into the next
     * turn instead of guessing from the model's answer.
     */
    @GetMapping("/memory/history")
    public Map<String, Object> conversationHistory(@RequestParam String conversationId) {
        List<Message> messages = chatMemory.get(ConversationIds.requireNonBlank(conversationId));

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
        String id = ConversationIds.requireNonBlank(conversationId);
        chatMemory.clear(id);
        return Map.of("conversationId", id, "cleared", true);
    }
}
