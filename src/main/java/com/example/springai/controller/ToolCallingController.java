package com.example.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/tools")
public class ToolCallingController {

    private final ChatClient chatClient;

    public ToolCallingController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/weather")
    public Map<String, String> queryWeather(@RequestParam(defaultValue = "What is the weather like in Tokyo right now?") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .functions("getCurrentWeather")
                .call()
                .content();

        return Map.of("prompt", prompt, "response", answer != null ? answer : "");
    }

    @GetMapping("/order")
    public Map<String, String> queryOrder(@RequestParam(defaultValue = "Can you give me the shipping status for order ORD-101?") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .functions("getOrderStatus")
                .call()
                .content();

        return Map.of("prompt", prompt, "response", answer != null ? answer : "");
    }

    @GetMapping("/multi")
    public Map<String, String> multiToolQuery(
            @RequestParam(defaultValue = "Check the weather in London, and also check the delivery status of order ORD-103.") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .functions("getCurrentWeather", "getOrderStatus")
                .call()
                .content();

        return Map.of("prompt", prompt, "response", answer != null ? answer : "");
    }
}
