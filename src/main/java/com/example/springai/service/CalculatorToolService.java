package com.example.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Tool callback for exact arithmetic, the canonical small-model weakness. The expression is evaluated by a
 * hand-written recursive-descent parser rather than a script engine: no code execution surface, and the
 * grammar is exactly the arithmetic the description advertises.
 */
@Configuration
public class CalculatorToolService {

    private static final Logger log = LoggerFactory.getLogger(CalculatorToolService.class);

    /**
     * @param expression arithmetic over +, -, *, /, %, ^ and parentheses, e.g. {@code (12.5 + 7.5) * 3}
     */
    public record Request(String expression) {}

    public record Result(String expression, String outcome) {}

    @Bean
    @Description("Evaluate an arithmetic expression with + - * / % ^ and parentheses; use for any numeric calculation")
    public Function<Request, Result> calculate() {
        return request -> {
            log.info("[SPRING-AI-TOOL] Executing calculate tool call for expression={}", request.expression());
            return new Result(request.expression(), evaluate(request.expression()));
        };
    }

    static String evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            return "error: empty expression";
        }
        try {
            Parser parser = new Parser(expression);
            double value = parser.parseExpression();
            if (!parser.atEnd()) {
                return "error: unexpected input at position " + parser.pos;
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return "error: result is not a finite number";
            }
            return value == Math.rint(value) && Math.abs(value) < 1e15
                    ? String.valueOf((long) value)
                    : String.valueOf(value);
        } catch (ArithmeticException ex) {
            return "error: " + ex.getMessage();
        }
    }

    private static final class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
        }

        boolean atEnd() {
            skipWhitespace();
            return pos >= input.length();
        }

        double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    value += parseTerm();
                } else if (match('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    value *= parseFactor();
                } else if (match('/')) {
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value /= divisor;
                } else if (match('%')) {
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            skipWhitespace();
            double base = parseUnary();
            skipWhitespace();
            if (match('^')) {
                double exponent = parseFactor();
                return Math.pow(base, exponent);
            }
            return base;
        }

        private double parseUnary() {
            skipWhitespace();
            if (match('-')) {
                return -parseUnary();
            }
            if (match('+')) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            skipWhitespace();
            if (match('(')) {
                double value = parseExpression();
                skipWhitespace();
                if (!match(')')) {
                    throw new ArithmeticException("missing closing parenthesis");
                }
                return value;
            }
            int start = pos;
            while (pos < input.length()
                    && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new ArithmeticException("expected a number at position " + pos);
            }
            try {
                return Double.parseDouble(input.substring(start, pos));
            } catch (NumberFormatException ex) {
                throw new ArithmeticException("malformed number at position " + start);
            }
        }

        private boolean match(char c) {
            if (pos < input.length() && input.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
