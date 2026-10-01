package com.example.springai.controller;

import com.example.springai.guardrail.PiiRedactor;
import com.example.springai.support.ConversationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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

    private static final String[] ALL_TOOLS = {
            "getCurrentWeather", "getOrderStatus", "calculate", "getCurrentDateTime", "searchKnowledgeBase"
    };

    private final ChatClient chatClient;
    private final ChatClient conversationalChatClient;
    private final PiiRedactor piiRedactor;

    public ToolCallingController(ChatClient chatClient,
                                 @Qualifier("conversationalChatClient") ChatClient conversationalChatClient,
                                 PiiRedactor piiRedactor) {
        this.chatClient = chatClient;
        this.conversationalChatClient = conversationalChatClient;
        this.piiRedactor = piiRedactor;
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

    /**
     * The agentic endpoint: the full toolbox is offered and the model chains whichever tools the question
     * needs — arithmetic, the clock, the order system and the knowledge base in a single turn.
     *
     * <p>Without a conversation id it is stateless, exactly as before. With one, the turn is stored and the
     * next turn can say "and times three?" — the same memory advisor the chat endpoints use, with the tools
     * still in play.
     */
    @GetMapping("/assistant")
    public Map<String, String> assistant(
            @RequestParam(defaultValue = "How many days of annual leave do I get, and what is 3 times that number?") String prompt,
            @RequestParam(required = false) String conversationId) {

        final String conversation = conversationId != null && !conversationId.isBlank()
                ? ConversationIds.requireNonBlank(conversationId)
                : null;
        ChatClient client = conversation == null ? chatClient : conversationalChatClient;

        String answer = client.prompt()
                .user(prompt)
                .toolNames(ALL_TOOLS)
                .options(LOW_TEMPERATURE)
                .advisors(advisors -> {
                    if (conversation != null) {
                        advisors.param(ChatMemory.CONVERSATION_ID, conversation);
                    }
                })
                .call()
                .content();

        Map<String, String> response = answered(prompt, answer);
        return conversation == null ? response
                : Map.of("conversationId", conversation, "prompt", prompt, "response", response.get("response"));
    }

    /**
     * The same agentic call, streamed. Tool calls execute first and their results are folded into the prompt,
     * so the first token a client sees already arrives after the tools ran; the stream is the model composing
     * its answer, not the tool-calling round-trips.
     */
    @GetMapping(value = "/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> assistantStream(
            @RequestParam(defaultValue = "How many days of annual leave do I get, and what is 3 times that number?") String prompt,
            @RequestParam(defaultValue = "assistant-1") String conversationId) {

        String conversation = ConversationIds.requireNonBlank(conversationId);
        return conversationalChatClient.prompt()
                .user(prompt)
                .toolNames(ALL_TOOLS)
                .options(LOW_TEMPERATURE)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation))
                .stream()
                .content()
                .map(piiRedactor::redact);
    }

    // An empty answer would otherwise look like a successful call; the tool log shows whether one ran.
    // Every tool answer passes through redaction, so a tool result echoing personal data does not leave here.
    private Map<String, String> answered(String prompt, String answer) {
        if (answer == null || answer.isBlank()) {
            log.warn("Model returned no completion for tool prompt: {}", prompt);
        }
        return Map.of("prompt", prompt, "response", answer == null ? "" : piiRedactor.redact(answer));
    }
}
