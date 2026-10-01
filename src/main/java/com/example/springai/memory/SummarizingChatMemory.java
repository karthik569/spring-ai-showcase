package com.example.springai.memory;

import com.example.springai.guardrail.PiiRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link ChatMemory} that keeps a rolling summary of turns that fall out of the recent window.
 *
 * <p>{@link org.springframework.ai.chat.memory.MessageWindowChatMemory} drops the oldest messages once the
 * window is full, so a long conversation silently forgets its opening. On this hardware the window has to
 * stay small — an unbounded transcript pushes the actual question out of a 1.5B model's context — so the
 * fix is not a bigger window but a compressed one: once the stored history passes {@code triggerMessages},
 * everything but the last {@code keepRecentMessages} is folded into a single summary message that stays at
 * the head of the transcript.
 *
 * <p>It is a decorator, so {@code MessageChatMemoryAdvisor} is untouched — it still reads and writes a plain
 * {@link ChatMemory} and never learns that the older turns were rewritten. Short conversations are
 * byte-identical to the plain window: below the trigger this class only forwards.
 *
 * <p>Every failure mode degrades toward keeping the transcript intact. A summarizer that throws or returns
 * blank leaves memory untouched rather than replacing history with nothing, because a lost conversation is
 * worse than a long one.
 */
public class SummarizingChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger("CONVERSATION_SUMMARY");

    static final String SUMMARY_PREFIX = "Summary of earlier conversation: ";

    // The whole transcript is handed to the summarizer in one prompt, so it is capped; a conversation that
    // outgrows this is summarized from its older half, which is what a rolling summary is for.
    private static final int MAX_TRANSCRIPT_CHARS = 6000;

    private final ChatMemory delegate;
    private final ChatClient summarizer;
    private final PiiRedactor piiRedactor;
    private final int triggerMessages;
    private final int keepRecentMessages;
    private final int maxSummaryChars;

    public SummarizingChatMemory(ChatMemory delegate,
                                 ChatClient summarizer,
                                 PiiRedactor piiRedactor,
                                 int triggerMessages,
                                 int keepRecentMessages,
                                 int maxSummaryChars) {
        this.delegate = delegate;
        this.summarizer = summarizer;
        this.piiRedactor = piiRedactor;
        // Below 2 kept messages the summary stops being a summary and becomes the whole conversation.
        this.triggerMessages = Math.max(2, triggerMessages);
        this.keepRecentMessages = Math.max(1, Math.min(keepRecentMessages, this.triggerMessages - 1));
        this.maxSummaryChars = Math.max(80, maxSummaryChars);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        delegate.add(conversationId, messages);
        compact(conversationId);
    }

    @Override
    public List<Message> get(String conversationId) {
        return delegate.get(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }

    private void compact(String conversationId) {
        List<Message> all = delegate.get(conversationId);
        if (all.size() <= triggerMessages) {
            return;
        }
        int keep = Math.min(keepRecentMessages, all.size() - 1);
        List<Message> older = List.copyOf(all.subList(0, all.size() - keep));
        List<Message> recent = new ArrayList<>(all.subList(all.size() - keep, all.size()));

        String summary;
        try {
            summary = summarize(older);
        } catch (RuntimeException ex) {
            // A failed summarizer must not cost the conversation; leave the transcript as the window left it.
            log.warn("[CONVERSATION-SUMMARY] summarization failed, keeping the transcript as-is: {}", ex.getMessage());
            return;
        }
        if (summary == null || summary.isBlank()) {
            return;
        }

        String safe = piiRedactor.redact(cap(summary, maxSummaryChars));
        List<Message> compacted = new ArrayList<>(recent.size() + 1);
        compacted.add(new SystemMessage(SUMMARY_PREFIX + safe));
        compacted.addAll(recent);

        delegate.clear(conversationId);
        delegate.add(conversationId, compacted);
        log.info("[CONVERSATION-SUMMARY] folded {} message(s) into a summary for conversationId={}",
                older.size(), conversationId);
    }

    private String summarize(List<Message> messages) {
        StringBuilder transcript = new StringBuilder();
        for (Message message : messages) {
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (transcript.length() > 0) {
                transcript.append('\n');
            }
            transcript.append(Objects.toString(message.getMessageType(), "MESSAGE")).append(": ").append(text);
        }
        if (transcript.length() == 0) {
            return null;
        }
        String prompt = cap(transcript.toString(), MAX_TRANSCRIPT_CHARS);
        return summarizer.prompt().user(prompt).call().content();
    }

    private static String cap(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
