package com.example.springai.guardrail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks personal data in text before it leaves the API. Regex-based and deliberately conservative: it is a
 * last line over model output, not a classifier, so it errs toward masking a lookalike rather than leaking
 * a real identifier.
 *
 * <p>Each rule is a named pattern so the audit trail can say <em>what</em> was masked without repeating the
 * value. Card numbers are Luhn-checked, because a 16-digit order id should not be reported as a PAN.
 */
@Component
public class PiiRedactor {

    private static final String MASK = "[redacted]";

    private record Rule(String name, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("email", Pattern.compile("\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b")),
            new Rule("phone", Pattern.compile("\\b(?:\\+\\d{1,3}[ -]?)?(?:\\(\\d{2,4}\\)[ -]?)?\\d{3}[ -]?\\d{3,4}[ -]?\\d{4}\\b")),
            new Rule("ssn", Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b")),
            new Rule("ipv4", Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")),
            new Rule("card", Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"))
    );

    private final boolean enabled;

    public PiiRedactor(@Value("${app.guardrails.redact-output:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** @return the text with PII masked, or the original when redaction is disabled */
    public String redact(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(match -> {
                String value = match.group();
                // A card rule that fires on a 16-digit number failing Luhn is almost certainly not a card.
                if ("card".equals(rule.name()) && !passesLuhn(value)) {
                    return value;
                }
                return MASK;
            });
        }
        return result;
    }

    /** @param text the model output to inspect
     *  @return the names of the rules that matched, deduplicated, in rule order */
    public List<String> findings(String text) {
        List<String> found = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        for (Rule rule : RULES) {
            var matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                String value = matcher.group();
                if ("card".equals(rule.name()) && !passesLuhn(value)) {
                    continue;
                }
                if (!found.contains(rule.name())) {
                    found.add(rule.name());
                }
                break;
            }
        }
        return found;
    }

    /** @return rule name to count, for a caller that wants an audit summary */
    public Map<String, Integer> summary(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return counts;
        }
        for (Rule rule : RULES) {
            var matcher = rule.pattern().matcher(text);
            int count = 0;
            while (matcher.find()) {
                if ("card".equals(rule.name()) && !passesLuhn(matcher.group())) {
                    continue;
                }
                count++;
            }
            if (count > 0) {
                counts.put(rule.name(), count);
            }
        }
        return counts;
    }

    private static boolean passesLuhn(String candidate) {
        String digits = candidate.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
