package com.example.springai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/tools")
public class ToolCallingController {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingController.class);

    // Whether a tool runs is the model's decision here: 0.2 is the temperature at which qwen2.5:0.5b called
    // both tools in most tests, while 0.0 stopped calling them and an "answer only from the tool result"
    // system prompt made the model describe the tool instead of using it.
    private static final OpenAiChatOptions LOW_TEMPERATURE = OpenAiChatOptions.builder()
            .temperature(0.2)
            .build();

    private final ChatClient chatClient;

    public ToolCallingController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/weather")
    public Map<String, String> queryWeather(@RequestParam(defaultValue = "What is the weather like in Tokyo right now?") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .toolNames("getCurrentWeather")
                .options(LOW_TEMPERATURE)
                .call()
                .content();

        return answered(prompt, answer);
    }

    @GetMapping("/order")
    public Map<String, String> queryOrder(@RequestParam(defaultValue = "Can you give me the shipping status for order ORD-101?") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .toolNames("getOrderStatus")
                .options(LOW_TEMPERATURE)
                .call()
                .content();

        return answered(prompt, answer);
    }

    @GetMapping("/multi")
    public Map<String, String> multiToolQuery(
            @RequestParam(defaultValue = "Check the weather in London, and also check the delivery status of order ORD-103.") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .toolNames("getCurrentWeather", "getOrderStatus")
                .call()
                .content();

        return answered(prompt, answer);
    }

    // An empty answer would otherwise look like a successful call; the tool log shows whether one ran.
    private static Map<String, String> answered(String prompt, String answer) {
        if (answer == null || answer.isBlank()) {
            log.warn("Model returned no completion for tool prompt: {}", prompt);
        }
        return Map.of("prompt", prompt, "response", answer == null ? "" : answer);
    }
}
