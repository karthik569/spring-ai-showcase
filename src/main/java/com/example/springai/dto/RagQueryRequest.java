package com.example.springai.dto;

import com.example.springai.support.ConversationIds;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param question required — Spring AI rejects a null query deeper in the stack, which would surface as a 500
 * @param filename optional metadata filter, restricting retrieval to one ingested document
 * @param conversationId optional. When present and not the first turn of that conversation, a follow-up is
 *                       resolved against the stored history before retrieval runs. Blank means "no
 *                       conversation" and is <em>not</em> a 400, unlike the memory endpoints where the id is the
 *                       whole point — that asymmetry is what keeps a request without the field behaving exactly
 *                       as it did before the field existed.
 */
public record RagQueryRequest(
        @NotBlank(message = "question must not be blank") String question,
        String filename,
        @Size(max = ConversationIds.MAX_CHARS,
                message = "conversationId is limited to {max} characters") String conversationId
) {}
