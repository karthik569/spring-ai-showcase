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

    @GetMapping("/calculate")
    public Map<String, String> calculate(@RequestParam(defaultValue = "What is 128 * 46 + 1024?") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .toolNames("calculate")
                .options(LOW_TEMPERATURE)
                .call()
                .content();

        return answered(prompt, answer);
    }

    @GetMapping("/datetime")
    public Map<String, String> dateTime(@RequestParam(defaultValue = "What time is it in London right now?") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .toolNames("getCurrentDateTime")
                .options(LOW_TEMPERATURE)
                .call()
                .content();

        return answered(prompt, answer);
    }

    // The agentic endpoint: the full toolbox is offered and the model chains whichever tools the question
    // needs — arithmetic, the clock, the order system and the knowledge base in a single turn.
    @GetMapping("/assistant")
    public Map<String, String> assistant(
            @RequestParam(defaultValue = "How many days of annual leave do I get, and what is 3 times that number?") String prompt) {
        String answer = chatClient.prompt()
                .user(prompt)
                .toolNames("getCurrentWeather", "getOrderStatus", "calculate", "getCurrentDateTime",
                        "searchKnowledgeBase")
                .options(LOW_TEMPERATURE)
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
