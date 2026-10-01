package com.example.springai.agent;

import com.example.springai.guardrail.PiiRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A bounded, inspectable tool loop.
 *
 * <p>Spring AI's built-in tool execution hides the round-trips inside {@code ChatModel.call}: a model that
 * keeps asking for tools runs until it stops, and the caller sees only the last message. This service drives
 * the same {@link ToolCallingManager} by hand. It turns internal execution off on the request, then alternates
 * between a model call and a tool execution for as many steps as the budget allows, recording every hop so the
 * decision trail is visible and one runaway generation cannot spin the loop forever.
 *
 * <p>Reflection is not a separate phase: each tool result is folded back into the conversation, so the model's
 * next turn is precisely the step where it decides whether the evidence is enough to answer.
 */
@Service
public class AgenticLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgenticLoopService.class);

    private static final String SYSTEM_PROMPT = """
            You are a careful assistant that works in steps. You have tools; use them when a question needs a
            fact you cannot derive.
            - Call one tool at a time, then read its result before deciding what to do next.
            - Never invent a tool result; if a tool failed, say so.
            - When you have enough information, answer in plain prose with no further tool call.
            """;

    private static final String OUT_OF_BUDGET_NUDGE =
            "You have used your whole tool budget. Answer the question now using only what you already have.";

    private static final int EXCERPT_CHARS = 240;

    /**
     * @param phase one of {@code tool_call}, {@code tool_result} or {@code answer}
     */
    public record Step(int step, String phase, String name, String detail) {}

    public record AgentRun(String question, String answer, List<Step> steps,
                           int modelCalls, int toolCalls, boolean budgetExhausted) {}

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final PiiRedactor piiRedactor;
    private final int maxSteps;
    private final Set<String> toolNames;

    public AgenticLoopService(ChatModel chatModel,
                              ToolCallingManager toolCallingManager,
                              PiiRedactor piiRedactor,
                              @Value("${app.agent.max-steps:6}") int maxSteps,
                              @Value("${app.agent.tools:getCurrentWeather,getOrderStatus,calculate,getCurrentDateTime,searchKnowledgeBase}") List<String> tools) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.piiRedactor = piiRedactor;
        this.maxSteps = maxSteps;
        this.toolNames = Set.copyOf(tools);
    }

    public AgentRun run(String question) {
        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(SYSTEM_PROMPT));
        conversation.add(new UserMessage(question));

        List<Step> steps = new ArrayList<>();
        int modelCalls = 0;
        int toolCalls = 0;
        String answer = null;

        for (int step = 1; step <= maxSteps && answer == null; step++) {
            Prompt prompt = new Prompt(conversation, toolOptions());
            ChatResponse response = chatModel.call(prompt);
            modelCalls++;

            if (!response.hasToolCalls()) {
                answer = text(response);
                steps.add(new Step(step, "answer", "", excerpt(answer)));
                break;
            }

            for (AssistantMessage.ToolCall call : response.getResult().getOutput().getToolCalls()) {
                toolCalls++;
                steps.add(new Step(step, "tool_call", call.name(), excerpt(call.arguments())));
            }

            ToolExecutionResult executed = toolCallingManager.executeToolCalls(prompt, response);
            conversation = new ArrayList<>(executed.conversationHistory());

            for (Message message : executed.conversationHistory()) {
                if (message instanceof ToolResponseMessage toolResponse) {
                    for (ToolResponseMessage.ToolResponse single : toolResponse.getResponses()) {
                        steps.add(new Step(step, "tool_result", single.name(), excerpt(single.responseData())));
                    }
                }
            }
        }

        boolean budgetExhausted = answer == null;
        if (budgetExhausted) {
            answer = answerWithoutTools(conversation);
            modelCalls++;
            steps.add(new Step(maxSteps + 1, "answer", "", excerpt(answer) + " (step budget reached)"));
        }

        log.info("[SPRING-AI-AGENT] steps={} modelCalls={} toolCalls={} budgetExhausted={}",
                steps.size(), modelCalls, toolCalls, budgetExhausted);

        return new AgentRun(question,
                answer == null ? "" : piiRedactor.redact(answer),
                List.copyOf(steps), modelCalls, toolCalls, budgetExhausted);
    }

    /**
     * The budget ran out with the model still asking for tools, so ask once more with tools switched off. A
     * partial answer beats none, and a fresh prompt (not a system message mid-conversation) is what every
     * OpenAI-compatible server accepts.
     */
    private String answerWithoutTools(List<Message> conversation) {
        List<Message> closing = new ArrayList<>(conversation);
        closing.add(new UserMessage(OUT_OF_BUDGET_NUDGE));
        try {
            return text(chatModel.call(new Prompt(closing, OpenAiChatOptions.builder().temperature(0.0).build())));
        } catch (RuntimeException ex) {
            log.warn("Final answer after budget exhaustion failed: {}", ex.getMessage());
            return "";
        }
    }

    private OpenAiChatOptions toolOptions() {
        return OpenAiChatOptions.builder()
                .toolNames(toolNames)
                .internalToolExecutionEnabled(false)
                .temperature(0.2)
                .build();
    }

    private String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String content = response.getResult().getOutput().getText();
        return content == null ? "" : content;
    }

    private String excerpt(String value) {
        if (value == null) {
            return "";
        }
        String flattened = value.replaceAll("\\s+", " ").trim();
        return flattened.length() <= EXCERPT_CHARS ? flattened : flattened.substring(0, EXCERPT_CHARS) + "...";
    }
}
