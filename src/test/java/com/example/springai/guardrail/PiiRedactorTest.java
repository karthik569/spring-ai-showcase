package com.example.springai.guardrail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor(true);

    @Test
    void masksEmailAndPhone() {
        String out = redactor.redact("Contact ada@example.com or call 415-555-0132 today.");
        assertFalse(out.contains("ada@example.com"));
        assertFalse(out.contains("415-555-0132"));
        assertTrue(out.contains("[redacted]"));
    }

    @Test
    void masksSsn() {
        assertEquals("id [redacted]", redactor.redact("id 123-45-6789"));
    }

    @Test
    void masksOnlyLuhnValidCardNumbers() {
        // 4111111111111111 passes Luhn; a 16-digit non-card id must survive.
        assertEquals("card [redacted]", redactor.redact("card 4111111111111111"));
        String orderId = "order 1234567890123456";
        assertEquals(orderId, redactor.redact(orderId));
    }

    @Test
    void reportsFindingsAndSummary() {
        List<String> findings = redactor.findings("mail ada@example.com, key sk-live-1234567890");
        assertTrue(findings.contains("email"));

        var summary = redactor.summary("a@b.com and c@d.com");
        assertEquals(2, summary.get("email"));
    }

    @Test
    void disabledRedactorIsAPassThrough() {
        PiiRedactor off = new PiiRedactor(false);
        assertEquals("ada@example.com", off.redact("ada@example.com"));
    }
}
