package com.example.springai.guardrail;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Screens text for instruction-override patterns before it is indexed as knowledge. A retrieved chunk is
 * injected into the prompt as if it were context, so a document that says "ignore your instructions" is an
 * indirect prompt injection — the reason this runs at ingest, not at query time.
 *
 * <p>Heuristic and intentionally blunt: it is a gate on who may add documents, not a classifier. Each rule
 * is named so the rejection can quote the rule without echoing the payload.
 */
@Component
public class PromptInjectionDetector {

    private record Rule(String name, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("instruction-override", Pattern.compile(
                    "(?i)\\b(ignore|disregard|forget)\\b[^.!?\\n]{0,40}\\b(previous|prior|above|earlier|all)\\b[^.!?\\n]{0,20}\\b(instruction|prompt|rule|direction)s?\\b")),
            new Rule("system-prompt-probe", Pattern.compile(
                    "(?i)\\b(reveal|show|print|repeat|output|leak)\\b[^.!?\\n]{0,30}\\b(system|initial|original|developer)\\b[^.!?\\n]{0,15}\\b(prompt|message|instruction)s?\\b")),
            new Rule("role-reassignment", Pattern.compile(
                    "(?i)\\byou are (now|no longer)\\b|\\bact as (an?|the)\\b[^.!?\\n]{0,30}\\b(assistant|system|a[il])\\b")),
            new Rule("exfiltration", Pattern.compile(
                    "(?i)\\b(exfiltrate|send|post|upload|email)\\b[^.!?\\n]{0,40}\\b(credentials?|api[ _-]?key|secret|password|token|env(ironment)? variables?|ssh)\\b")),
            new Rule("tool-coercion", Pattern.compile(
                    "(?i)\\b(call|invoke|execute|run)\\b[^.!?\\n]{0,25}\\b(tool|function|command|shell|code)\\b[^.!?\\n]{0,25}\\bwithout\\b"))
    );

    /**
     * @param text the document or message to screen
     * @return the names of the rules that matched, empty when the text is clean
     */
    public List<String> scan(String text) {
        List<String> matches = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return matches;
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find() && !matches.contains(rule.name())) {
                matches.add(rule.name());
            }
        }
        return matches;
    }

    public boolean isSuspicious(String text) {
        return !scan(text).isEmpty();
    }
}
