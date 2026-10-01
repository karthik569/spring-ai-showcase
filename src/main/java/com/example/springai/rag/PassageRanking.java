package com.example.springai.rag;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Locale;

/**
 * The prompt and parsing behind a listwise model call over candidate passages.
 *
 * <p>Both the re-ranker and the context grader ask the same shape of question — "here are numbered
 * passages, pick some" — so the numbering, the excerpting and the tolerant index parser live once. They
 * differ only in the one instruction line and in how an empty answer is read.
 */
final class PassageRanking {

    private PassageRanking() {
    }

    static String prompt(String question, List<Document> passages, int excerptChars, String instruction) {
        StringBuilder prompt = new StringBuilder()
                .append("Question: ").append(question).append("\n\nPassages:\n");
        for (int i = 0; i < passages.size(); i++) {
            prompt.append(i + 1).append(". ").append(excerpt(passages.get(i).getText(), excerptChars)).append('\n');
        }
        prompt.append('\n').append(instruction);
        return prompt.toString();
    }

    /**
     * The 1-based passage numbers the model named, deduplicated and in the order it named them.
     *
     * <p>Tolerant on purpose: the local model wraps numbers in prose, repeats them and occasionally invents
     * one. Anything outside {@code 1..size} is dropped; a reply with no usable number returns an empty list,
     * which the caller reads as "no opinion" rather than "nothing is relevant".
     */
    static List<Integer> parse(String reply, int size) {
        if (reply == null || reply.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<Integer> ordered = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(reply);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group());
            if (index >= 1 && index <= size) {
                ordered.add(index);
            }
        }
        return List.copyOf(ordered);
    }

    static boolean saysNone(String reply) {
        return reply != null && reply.toUpperCase(Locale.ROOT).contains("NONE");
    }

    static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "...";
    }
}
