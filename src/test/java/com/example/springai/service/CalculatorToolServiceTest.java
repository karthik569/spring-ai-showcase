package com.example.springai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculatorToolServiceTest {

    private final CalculatorToolService service = new CalculatorToolService();

    private String outcome(String expression) {
        return service.calculate().apply(new CalculatorToolService.Request(expression)).outcome();
    }

    @Test
    void evaluatesPrecedenceParenthesesAndPower() {
        assertEquals("6912", outcome("128 * 46 + 1024"));
        assertEquals("60", outcome("(12.5 + 7.5) * 3"));
        assertEquals("25", outcome("2 ^ 3 ^ 2 - 487"));
        assertEquals("14", outcome("2 + 3 * 4"));
    }

    @Test
    void handlesNegativesDecimalsAndModulo() {
        assertEquals("-6", outcome("-2 * 3"));
        assertEquals("2.5", outcome("5 / 2"));
        assertEquals("1", outcome("10 % 3"));
    }

    @Test
    void reportsErrorsInsteadOfThrowing() {
        assertTrue(outcome("5 / 0").startsWith("error: division by zero"));
        assertTrue(outcome("").startsWith("error:"));
        assertTrue(outcome("2 +").startsWith("error:"));
        assertTrue(outcome("sqrt(4)").startsWith("error:"));
        assertTrue(outcome("(2 + 3").startsWith("error:"));
    }
}
