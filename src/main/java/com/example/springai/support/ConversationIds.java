package com.example.springai.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The conversation-id rule, shared by the endpoints that take one. It lives here rather than in a controller
 * because the chat-memory endpoints and RAG follow-up resolution both need the exact same limit, and the 36 is
 * not a preference: the Spring AI H2 chat-memory schema stores the id in {@code VARCHAR(36)}, so a longer value
 * fails on insert deep inside JDBC instead of at the API boundary.
 */
public final class ConversationIds {

    public static final int MAX_CHARS = 36;

    private ConversationIds() {
    }

    public static boolean isUsable(String conversationId) {
        return conversationId != null && !conversationId.isBlank() && conversationId.length() <= MAX_CHARS;
    }

    /** For endpoints where a conversation is the whole point, so a bad id is an error rather than a default. */
    public static String requireNonBlank(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId must not be blank");
        }
        if (conversationId.length() > MAX_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "conversationId is limited to " + MAX_CHARS + " characters");
        }
        return conversationId;
    }
}
