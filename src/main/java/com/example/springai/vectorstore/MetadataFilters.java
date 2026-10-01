package com.example.springai.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.function.Predicate;

/**
 * Evaluates a Spring AI {@link Filter.Expression} against a document's metadata.
 *
 * <p>The keyword half of hybrid retrieval runs in this process, so it cannot hand a filter expression to the
 * store the way the vector half does — it has to decide for itself whether a document matches. Only the
 * operators this application builds are supported ({@code EQ} on a metadata key, and the {@code AND}/{@code OR}
 * /{@code NOT} that compose them); anything else matches everything rather than throwing, so an exotic filter
 * weakens the keyword leg instead of failing the request.
 */
final class MetadataFilters {

    private MetadataFilters() {
    }

    static Predicate<Document> toPredicate(Filter.Expression expression) {
        if (expression == null) {
            return document -> true;
        }
        return document -> matches(expression, document);
    }

    private static boolean matches(Filter.Expression expression, Document document) {
        return switch (expression.type()) {
            case AND -> matches(asExpression(expression.left()), document)
                    && matches(asExpression(expression.right()), document);
            case OR -> matches(asExpression(expression.left()), document)
                    || matches(asExpression(expression.right()), document);
            case NOT -> !matches(asExpression(expression.left()), document);
            case ISNULL -> valueOf(document, expression) == null;
            case ISNOTNULL -> valueOf(document, expression) != null;
            case EQ -> equal(valueOf(document, expression), literal(expression));
            case NE -> !equal(valueOf(document, expression), literal(expression));
            case IN -> contained(valueOf(document, expression), literal(expression));
            case NIN -> !contained(valueOf(document, expression), literal(expression));
            default -> true;
        };
    }

    private static Filter.Expression asExpression(Filter.Operand operand) {
        return operand instanceof Filter.Expression expression ? expression : null;
    }

    private static Object valueOf(Document document, Filter.Expression expression) {
        if (!(expression.left() instanceof Filter.Key key)) {
            return null;
        }
        return document.getMetadata().get(key.key());
    }

    private static Object literal(Filter.Expression expression) {
        return expression.right() instanceof Filter.Value value ? value.value() : null;
    }

    private static boolean equal(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == null && expected == null;
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    @SuppressWarnings("unchecked")
    private static boolean contained(Object actual, Object expected) {
        if (actual == null || !(expected instanceof List<?> list)) {
            return false;
        }
        return ((List<Object>) list).stream().anyMatch(candidate -> equal(actual, candidate));
    }
}
