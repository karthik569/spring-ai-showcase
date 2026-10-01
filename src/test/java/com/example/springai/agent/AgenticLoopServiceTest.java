package com.example.springai.agent;

import com.example.springai.guardrail.PiiRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the loop with a scripted model and a stand-in tool executor, so the assertions are about the loop's
 * own behaviour — how many rounds it runs, what it records, when it stops — not about a model's tool choice,
 * which is the live-endpoint test's job.
 */
class AgenticLoopServiceTest {

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final FakeToolCallingManager toolManager = new FakeToolCallingManager();
    private final PiiRedactor redactor = new PiiRedactor(true);

    @Test
    void runsOneToolThenAnswers() {
        model.enqueue(
                toolCall("calculate", "{\"expression\":\"3*14\"}"),
                answer("You get 42 days of leave."));

        AgenticLoopService.AgentRun run = service(6).run("leave times three?");

        assertThat(run.answer()).isEqualTo("You get 42 days of leave.");
        assertThat(run.toolCalls()).isEqualTo(1);
        assertThat(run.modelCalls()).isEqualTo(2);
        assertThat(run.budgetExhausted()).isFalse();
        assertThat(run.steps()).extracting(AgenticLoopService.Step::phase)
                .containsExactly("tool_call", "tool_result", "answer");
        assertThat(run.steps().get(0).name()).isEqualTo("calculate");
        assertThat(run.steps().get(1).detail()).isEqualTo("42");
    }

    @Test
    void disablesInternalToolExecutionAndAdvertisesTheToolbox() {
        model.enqueue(toolCall("calculate", "{\"expression\":\"1+1\"}"), answer("2"));

        service(6).run("what is one plus one?");

        ToolCallingChatOptions first = (ToolCallingChatOptions) model.prompts.get(0).getOptions();
        assertThat(first.getInternalToolExecutionEnabled()).isFalse();
        assertThat(first.getToolNames()).contains("calculate", "searchKnowledgeBase");
    }

    @Test
    void executesEveryToolCallInOneRound() {
        ChatResponse twoCalls = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "calculate", "{\"expression\":\"2+2\"}"),
                        new AssistantMessage.ToolCall("c2", "function", "getCurrentDateTime", "{\"zone\":\"UTC\"}")))
                .build())));
        model.enqueue(twoCalls, answer("Four, at the recorded time."));

        AgenticLoopService.AgentRun run = service(6).run("how many is two plus two, and when?");

        assertThat(run.toolCalls()).isEqualTo(2);
        assertThat(run.modelCalls()).isEqualTo(2);
        assertThat(run.steps()).filteredOn(step -> step.phase().equals("tool_call"))
                .extracting(AgenticLoopService.Step::name)
                .containsExactly("calculate", "getCurrentDateTime");
    }

    @Test
    void stopsAtTheStepBudgetAndAnswersWithoutTools() {
        model.enqueue(
                toolCall("calculate", "{\"expression\":\"1+1\"}"),
                toolCall("calculate", "{\"expression\":\"2+2\"}"),
                answer("Best effort: two tools ran."));

        AgenticLoopService.AgentRun run = service(2).run("keep calculating");

        assertThat(run.budgetExhausted()).isTrue();
        assertThat(run.toolCalls()).isEqualTo(2);
        assertThat(run.modelCalls()).isEqualTo(3);
        assertThat(run.answer()).isEqualTo("Best effort: two tools ran.");
        // The final call re-prompts with no tools, so the model cannot ask for another.
        assertThat(((ToolCallingChatOptions) model.prompts.get(2).getOptions()).getToolNames()).isEmpty();
    }

    @Test
    void redactsPiiInTheFinalAnswer() {
        model.enqueue(answer("Write to jane.doe@example.com for the details."));

        AgenticLoopService.AgentRun run = service(6).run("who owns the runbook?");

        assertThat(run.answer()).contains("[redacted]").doesNotContain("jane.doe@example.com");
    }

    private AgenticLoopService service(int maxSteps) {
        return new AgenticLoopService(model, toolManager, redactor, maxSteps,
                List.of("calculate", "getCurrentDateTime", "searchKnowledgeBase"));
    }

    private static ChatResponse toolCall(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** Returns the next scripted response, and remembers every prompt so the options can be asserted. */
    private static final class ScriptedChatModel implements ChatModel {

        private final Deque<ChatResponse> responses = new ArrayDeque<>();
        private final List<Prompt> prompts = new ArrayList<>();

        void enqueue(ChatResponse... batch) {
            for (ChatResponse response : batch) {
                responses.add(response);
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return responses.poll();
        }
    }

    /** Stands in for {@code DefaultToolCallingManager}, stubbing a result so no real tool bean is needed. */
    private static final class FakeToolCallingManager implements ToolCallingManager {

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
            AssistantMessage assistant = response.getResult().getOutput();
            List<Message> history = new ArrayList<>(prompt.getInstructions());
            history.add(assistant);
            List<ToolResponseMessage.ToolResponse> results = assistant.getToolCalls().stream()
                    .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(), resultFor(call.name())))
                    .toList();
            history.add(ToolResponseMessage.builder().responses(results).build());
            return DefaultToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(false)
                    .build();
        }

        private String resultFor(String name) {
            return "calculate".equals(name) ? "42" : "ok";
        }
    }
}
