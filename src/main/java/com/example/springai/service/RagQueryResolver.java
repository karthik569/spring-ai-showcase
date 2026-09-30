package com.example.springai.service;

import com.example.springai.support.ConversationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a follow-up into text retrieval can actually answer, and writes RAG turns back into chat memory.
 *
 * <p>Why this exists as a service and not as an advisor: {@code QuestionAnswerAdvisor} builds its
 * {@code SearchRequest} from {@code prompt.getUserMessage().getText()} and then <em>replaces</em> that text with
 * the rendered context template, while {@code MessageChatMemoryAdvisor} injects history as separate messages and
 * never touches the user text. So memory is in the prompt before retrieval runs yet is invisible to the retrieval
 * query — attaching the memory advisor to the RAG client would change nothing here. The query string itself has
 * to become standalone, before the advisor sees it.
 *
 * <p>What it is measurably worth on this corpus is in {@code docs/SESSION-HANDOFF.md} §Phase 0: two of three
 * follow-ups already retrieve their gold chunk at rank 1 unresolved. The one that does not is not a ranking
 * failure but a floor failure — the correct chunk scores 0.166 against the shipped 0.2 threshold and is dropped,
 * so the answer is generated from a chunk that cannot answer it, with a citation list that looks legitimate.
 */
@Service
public class RagQueryResolver {

    private static final Logger log = LoggerFactory.getLogger(RagQueryResolver.class);

    /** Why no rewrite happened, reported to the caller instead of being inferred from a silent fallback. */
    public enum Outcome {
        /** {@code app.rag.follow-up.enabled=false}: every request is the single-shot one this used to be. */
        DISABLED,
        /** No usable conversation id, so there is no history to resolve against. */
        NO_CONVERSATION,
        /** A conversation id was given but nothing is stored under it yet. */
        EMPTY_MEMORY,
        /** Resolution is configured to need a pronoun or leading conjunction and this question has neither. */
        SKIPPED_BY_TRIGGER,
        /** The model declined, echoed, or produced something that failed validation. */
        PASSTHROUGH,
        /** The retrieval query differs from what the caller asked. */
        REWRITTEN
    }

    /**
     * @param retrievalQuery the text that actually goes into the vector search — always reported, so the
     *                       citation list cannot advertise chunks the reader has no way to know were fetched
     *                       against a different question than the one they typed
     */
    public record Resolution(String retrievalQuery, Outcome outcome, long rewriteTimeMs) {

        public boolean followUpResolved() {
            return outcome == Outcome.REWRITTEN;
        }
    }

    // One line per turn, and the local model's window is small enough that an unbounded transcript pushes the
    // actual question out of the prompt.
    private static final int LINE_CHARS = 200;

    /**
     * Demonstratives that carry an unresolved antecedent. {@code which} is deliberately absent: rewriting
     * "and which of those are covered?" legitimately keeps the word, and rejecting it would throw away a good
     * rewrite. A surviving word from this list is only disqualifying when the rewrite borrowed nothing from the
     * conversation either — see {@link #namesSomethingFromTheConversation}, which exists because the shipped
     * model produced a correct substitution that still began "How long does it take…".
     */
    private static final Pattern DEMONSTRATIVE =
            Pattern.compile("\\b(it|that|this|these|those|they|them)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern LEADING_CONJUNCTION = Pattern.compile("(?i)^(and|so|but|or|then)\\b");

    private static final Pattern WORD = Pattern.compile("[\\p{L}]{4,}");

    /**
     * Words a rewrite can add without naming an antecedent. The list only has to cover what a re-worded pronoun
     * smuggles in, and a borrow from it counts as no evidence at all.
     */
    private static final Set<String> FUNCTION_WORDS = Set.of(
            "what", "when", "where", "which", "who", "whom", "how", "that", "this", "these", "those", "they",
            "them", "their", "your", "have", "has", "had", "does", "did", "with", "from", "into", "about",
            "would", "could", "should", "will", "been", "were", "than", "then", "there", "here", "some",
            "such", "also", "just", "like", "need", "want", "same", "question", "rewrite", "rewritten");

    // A 494M model labels its output often enough that the label has to go before anything else is judged. The
    // separator is required, so "Same day delivery?" and "Same-day courier costs?" survive intact while
    // "Same: ..." and "Rewritten question:" do not; a bare "SAME" is caught by the equality check in resolve().
    private static final Pattern LEADING_LABEL = Pattern.compile(
            "^(SAME|REWRIT\\w*|REVISED|STANDALONE|QUESTION)(?: QUESTION)?\\s*[:.!?]+\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final String INSTRUCTIONS = """
            Given the conversation, rewrite the final question so it is understandable on its own. \
            Replace pronouns such as it, that, this, those, which with the thing they refer to. \
            Keep it to one line. If it already stands alone, reply SAME.\
            """;

    private final ChatClient rewriter;
    private final ChatMemory chatMemory;
    private final boolean enabled;
    private final int maxHistoryTurns;
    private final int maxQueryChars;
    private final boolean requireTrigger;

    public RagQueryResolver(@Qualifier("ragQueryRewriter") ChatClient rewriter,
                            ChatMemory chatMemory,
                            @Value("${app.rag.follow-up.enabled:true}") boolean enabled,
                            @Value("${app.rag.follow-up.max-history-turns:2}") int maxHistoryTurns,
                            @Value("${app.rag.follow-up.max-query-chars:300}") int maxQueryChars,
                            @Value("${app.rag.follow-up.require-trigger:false}") boolean requireTrigger) {
        this.rewriter = rewriter;
        this.chatMemory = chatMemory;
        this.enabled = enabled;
        this.maxHistoryTurns = Math.max(1, maxHistoryTurns);
        this.maxQueryChars = Math.max(16, maxQueryChars);
        this.requireTrigger = requireTrigger;
    }

    /**
     * Never returns a query the caller did not ask about: every rejection falls back to {@code question} and
     * says so through {@link Outcome}. Backend failures are <em>not</em> caught here — an unreachable model is a
     * 503 from {@code GlobalExceptionHandler}, not a reason to quietly answer the pronoun instead.
     */
    public Resolution resolve(String question, String conversationId) {
        if (!enabled) {
            return passthrough(question, Outcome.DISABLED);
        }
        if (!ConversationIds.isUsable(conversationId)) {
            return passthrough(question, Outcome.NO_CONVERSATION);
        }
        List<Message> history = chatMemory.get(conversationId);
        if (history.stream().noneMatch(message -> message.getMessageType() == MessageType.USER)) {
            return passthrough(question, Outcome.EMPTY_MEMORY);
        }
        if (requireTrigger && !looksLikeFollowUp(question)) {
            return passthrough(question, Outcome.SKIPPED_BY_TRIGGER);
        }

        long start = System.nanoTime();
        List<Message> recent = lastTurns(history);
        String modelOutput = rewriter.prompt()
                .user(buildPrompt(question, recent))
                .call()
                .content();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        String candidate = sanitize(modelOutput);
        if (candidate.isEmpty() || candidate.equalsIgnoreCase("SAME")
                || candidate.equalsIgnoreCase(collapse(question))) {
            return passthrough(question, Outcome.PASSTHROUGH, elapsedMs);
        }
        // The point is substituting the antecedent, so a rewrite that still points at the pronoun is suspect —
        // but suspect only until it has said something the conversation said. Live traffic discarded
        // "How long does it take to submit the home office equipment stipend?" for keeping a placeholder "it",
        // and the request then cited the wrong section at 0.219 while the answer was "I don't have enough
        // context". Over-rejection is not free: it reinstates the exact failure this service exists to remove.
        if (DEMONSTRATIVE.matcher(question).find() && DEMONSTRATIVE.matcher(candidate).find()
                && !namesSomethingFromTheConversation(candidate, question, recent)) {
            log.info("[RAG-REWRITE] Discarded '{}'; a demonstrative survived and nothing was borrowed from the"
                    + " conversation", candidate);
            return passthrough(question, Outcome.PASSTHROUGH, elapsedMs);
        }
        String capped = cap(candidate);
        if (capped == null) {
            return passthrough(question, Outcome.PASSTHROUGH, elapsedMs);
        }
        log.info("[RAG-REWRITE] '{}' -> '{}' in {} ms", question, capped, elapsedMs);
        return new Resolution(capped, Outcome.REWRITTEN, elapsedMs);
    }

    /**
     * Stores the exchange so the next follow-up has an antecedent to resolve against. The raw question is stored,
     * not the resolved one, so {@code GET /chat/memory/history} keeps meaning "what the user typed". The answer is
     * stored even when retrieval found nothing, because "what's the budget for that?" refers to the assistant's
     * own prose and dropping it deletes the antecedent.
     *
     * <p>The hazard to know about: these turns share the six-message window with {@code /chat/memory}, so one RAG
     * exchange displaces two chat messages.
     */
    public void remember(String conversationId, String rawQuestion, String answer) {
        if (!enabled || !ConversationIds.isUsable(conversationId)
                || rawQuestion == null || rawQuestion.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        chatMemory.add(conversationId, List.of(new UserMessage(rawQuestion), new AssistantMessage(answer)));
    }

    private static Resolution passthrough(String question, Outcome outcome) {
        return new Resolution(question, outcome, 0);
    }

    private static Resolution passthrough(String question, Outcome outcome, long elapsedMs) {
        return new Resolution(question, outcome, elapsedMs);
    }

    private String buildPrompt(String question, List<Message> recent) {
        StringBuilder prompt = new StringBuilder(INSTRUCTIONS).append("\nConversation:\n");
        for (Message message : recent) {
            prompt.append(message.getMessageType() == MessageType.USER ? "user: " : "assistant: ")
                    .append(truncate(collapse(message.getText()))).append('\n');
        }
        return prompt.append("Question: ").append(collapse(question)).append("\nRewritten question:").toString();
    }

    /** The newest {@code maxHistoryTurns} user/assistant pairs, oldest first. */
    private List<Message> lastTurns(List<Message> history) {
        List<Message> kept = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && kept.size() < maxHistoryTurns * 2; i--) {
            Message message = history.get(i);
            if (message.getMessageType() == MessageType.USER || message.getMessageType() == MessageType.ASSISTANT) {
                kept.add(0, message);
            }
        }
        return kept;
    }

    /**
     * Whether the rewrite took a detail from the conversation that the typed question never said — the positive
     * evidence that an antecedent was substituted, which is what the demonstrative rule is really testing for.
     * It also rejects an invented noun: a rewrite that names something the corpus and the transcript never
     * mentioned is the model answering rather than resolving, and searching that is how a hallucination becomes
     * a citation.
     */
    private static boolean namesSomethingFromTheConversation(String candidate, String question,
                                                            List<Message> recentHistory) {
        Set<String> asked = wordsOf(question);
        Set<String> conversation = wordsOf(recentHistory.stream()
                .map(Message::getText)
                .collect(Collectors.joining(" ")));
        return wordsOf(candidate).stream()
                .filter(word -> !asked.contains(word))
                .anyMatch(word -> !FUNCTION_WORDS.contains(word) && conversation.contains(word));
    }

    /** Lower-cased content words, which is coarse on purpose: a substring match would score {@code "it"} inside
     *  every word that contains those two letters. */
    private static Set<String> wordsOf(String text) {
        Set<String> words = new HashSet<>();
        Matcher matcher = WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private String sanitize(String modelOutput) {
        if (modelOutput == null) {
            return "";
        }
        String firstLine = modelOutput.split("\\R", 2)[0];
        return LEADING_LABEL.matcher(collapse(firstLine)).replaceFirst("");
    }

    /**
     * @return the capped query, or {@code null} when the only clean cut would leave a half sentence — searching
     *         a truncated clause is worse than searching the original question
     */
    private String cap(String candidate) {
        if (candidate.length() <= maxQueryChars) {
            return candidate;
        }
        int cut = candidate.lastIndexOf(' ', maxQueryChars);
        if (cut <= 0) {
            return null;
        }
        String capped = candidate.substring(0, cut).trim();
        char last = capped.charAt(capped.length() - 1);
        return last == '?' || last == '.' ? capped : null;
    }

    private static boolean looksLikeFollowUp(String question) {
        String text = question.trim();
        return DEMONSTRATIVE.matcher(text).find() || LEADING_CONJUNCTION.matcher(text).find();
    }

    private static String collapse(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String text) {
        return text.length() <= LINE_CHARS ? text : text.substring(0, LINE_CHARS) + "...";
    }
}
