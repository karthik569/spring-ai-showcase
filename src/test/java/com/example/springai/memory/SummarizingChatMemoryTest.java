package com.example.springai.memory;

import com.example.springai.config.AiConfig;
import com.example.springai.guardrail.PiiRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rolling summary, asserted offline against an in-memory repository and a scripted summarizer.
 *
 * <p>The claim under test is the shape of what the memory advisor will inject: below the trigger the
 * transcript is untouched; above it, one summary at the head and the last few turns verbatim; and every
 * failure keeps the transcript rather than replacing it with nothing.
 */
class SummarizingChatMemoryTest {

    private static final int TRIGGER = 8;
    private static final int KEEP = 4;

    @Test
    void belowTheTriggerTheTranscriptIsUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        ChatMemory memory = new SummarizingChatMemory(window(), summarizer(calls, () -> "unused"),
                new PiiRedactor(true), TRIGGER, KEEP, 400);

        for (int i = 1; i <= TRIGGER; i++) {
            memory.add("c1", List.of(new UserMessage("m" + i)));
        }

        List<Message> stored = memory.get("c1");
        assertEquals(TRIGGER, stored.size());
        assertEquals("m1", stored.get(0).getText());
        assertEquals(0, calls.get(), "the summarizer must not run before the trigger");
    }

    @Test
    void crossingTheTriggerFoldsOlderTurnsIntoOneSummaryMessage() {
        AtomicInteger calls = new AtomicInteger();
        ChatMemory memory = new SummarizingChatMemory(window(), summarizer(calls, () -> "the user asked about leave"),
                new PiiRedactor(true), TRIGGER, KEEP, 400);

        for (int i = 1; i <= TRIGGER + 1; i++) {
            memory.add("c1", List.of(new UserMessage("m" + i)));
        }

        List<Message> stored = memory.get("c1");
        assertEquals(KEEP + 1, stored.size(), "one summary message plus the kept recent turns");
        assertInstanceOf(SystemMessage.class, stored.get(0));
        assertTrue(stored.get(0).getText().startsWith(SummarizingChatMemory.SUMMARY_PREFIX));
        assertTrue(stored.get(0).getText().contains("the user asked about leave"));
        assertEquals("m6", stored.get(1).getText());
        assertEquals("m9", stored.get(4).getText());
        assertEquals(1, calls.get(), "exactly one summarization call");
    }

    @Test
    void aFailingSummarizerLeavesTheTranscriptIntact() {
        ChatMemory memory = new SummarizingChatMemory(window(), summarizer(new AtomicInteger(),
                () -> {
                    throw new RuntimeException("model down");
                }),
                new PiiRedactor(true), TRIGGER, KEEP, 400);

        for (int i = 1; i <= TRIGGER + 1; i++) {
            memory.add("c1", List.of(new UserMessage("m" + i)));
        }

        List<Message> stored = memory.get("c1");
        assertEquals(TRIGGER + 1, stored.size(), "a failed summary must not drop turns");
        assertEquals("m1", stored.get(0).getText());
    }

    @Test
    void aBlankSummaryLeavesTheTranscriptIntact() {
        ChatMemory memory = new SummarizingChatMemory(window(), summarizer(new AtomicInteger(), () -> "   "),
                new PiiRedactor(true), TRIGGER, KEEP, 400);

        for (int i = 1; i <= TRIGGER + 1; i++) {
            memory.add("c1", List.of(new UserMessage("m" + i)));
        }

        assertEquals(TRIGGER + 1, memory.get("c1").size());
    }

    @Test
    void piiInTheSummaryIsRedactedBeforeItIsStored() {
        ChatMemory memory = new SummarizingChatMemory(window(),
                summarizer(new AtomicInteger(), () -> "the user's email is alice@example.com"),
                new PiiRedactor(true), TRIGGER, KEEP, 400);

        for (int i = 1; i <= TRIGGER + 1; i++) {
            memory.add("c1", List.of(new UserMessage("m" + i)));
        }

        String summary = memory.get("c1").get(0).getText();
        assertTrue(summary.contains("[redacted]"));
        assertFalse(summary.contains("alice@example.com"));
    }

    @Test
    void aBlankTurnCountsTowardTheTriggerButIsNotSentToTheSummarizer() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> transcript = new AtomicReference<>();
        ChatMemory memory = new SummarizingChatMemory(window(), summarizer(calls, () -> "summary", transcript),
                new PiiRedactor(true), TRIGGER, KEEP, 400);

        memory.add("c1", List.of(new UserMessage("m1")));
        memory.add("c1", List.of(new UserMessage("m2")));
        memory.add("c1", List.of(new AssistantMessage(""))); // blank: counts as a message, carries no text
        memory.add("c1", List.of(new UserMessage("m4")));
        memory.add("c1", List.of(new UserMessage("m5")));
        memory.add("c1", List.of(new UserMessage("m6")));
        memory.add("c1", List.of(new UserMessage("m7")));
        memory.add("c1", List.of(new UserMessage("m8")));
        memory.add("c1", List.of(new UserMessage("m9"))); // crosses the trigger

        assertEquals(1, calls.get());
        assertEquals(4, transcript.get().split("\n").length,
                "the blank turn is skipped, so the summary sees only the text-bearing older messages");
        assertEquals(KEEP + 1, memory.get("c1").size());
    }

    @Test
    void theConfigChoosesBetweenThePlainWindowAndTheSummary() {
        ChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatClient summarizer = summarizer(new AtomicInteger(), () -> "summary");
        PiiRedactor redactor = new PiiRedactor(true);

        ChatMemory wrapped = new AiConfig().chatMemory(repo, summarizer, redactor, true, TRIGGER, KEEP, 30, 400);
        assertInstanceOf(SummarizingChatMemory.class, wrapped);

        ChatMemory plain = new AiConfig().chatMemory(repo, summarizer, redactor, false, TRIGGER, KEEP, 30, 400);
        assertFalse(plain instanceof SummarizingChatMemory, "summarization off is the plain window");
    }

    private static ChatMemory window() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(30)
                .build();
    }

    private static ChatClient summarizer(AtomicInteger calls, Supplier<String> responder) {
        return summarizer(calls, responder, null);
    }

    private static ChatClient summarizer(AtomicInteger calls, Supplier<String> responder,
                                         AtomicReference<String> lastPrompt) {
        return (ChatClient) java.lang.reflect.Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.class},
                (proxy, method, args) -> "prompt".equals(method.getName())
                        ? requestSpec(calls, responder, lastPrompt)
                        : null);
    }

    private static Object requestSpec(AtomicInteger calls, Supplier<String> responder,
                                      AtomicReference<String> lastPrompt) {
        return java.lang.reflect.Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.ChatClientRequestSpec.class},
                (proxy, method, args) -> {
                    if ("user".equals(method.getName()) && lastPrompt != null && args != null && args.length > 0) {
                        lastPrompt.set(String.valueOf(args[0]));
                    }
                    return "call".equals(method.getName()) ? callSpec(calls, responder) : proxy;
                });
    }

    private static Object callSpec(AtomicInteger calls, Supplier<String> responder) {
        return java.lang.reflect.Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.CallResponseSpec.class},
                (proxy, method, args) -> {
                    if ("content".equals(method.getName())) {
                        calls.incrementAndGet();
                        return responder.get();
                    }
                    return null;
                });
    }
}
