package com.example.springai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The re-ranker's parsing rules, asserted offline against a scripted reply.
 *
 * <p>What matters is that a noisy small model can only ever reorder, never drop: a reply that names some
 * passages puts those first and appends the rest in their fused order, and any reply it cannot use — garbage,
 * blank, or an exception — leaves the fused order exactly as it was.
 */
class LlmRerankerTest {

    @Test
    void aValidIndexListReordersAndAppendsTheUnmentioned() {
        AtomicInteger calls = new AtomicInteger();
        LlmReranker reranker = new LlmReranker(client(calls, () -> "3, 1"), 200);

        List<Document> ranked = reranker.rerank("any question", documents());

        assertEquals(List.of("d3", "d1", "d2"), ids(ranked),
                "named passages come first in the model's order, the rest keep their fused position");
        assertEquals(1, calls.get());
    }

    @Test
    void anUnparseableReplyKeepsTheFusedOrder() {
        LlmReranker reranker = new LlmReranker(client(new AtomicInteger(), () -> "I am not sure about these."), 200);

        List<Document> ranked = reranker.rerank("any question", documents());

        assertEquals(List.of("d1", "d2", "d3"), ids(ranked));
    }

    @Test
    void aBlankOrMissingModelKeepsTheFusedOrder() {
        LlmReranker blank = new LlmReranker(client(new AtomicInteger(), () -> "   "), 200);
        assertEquals(List.of("d1", "d2", "d3"), ids(blank.rerank("any question", documents())));

        LlmReranker failing = new LlmReranker(client(new AtomicInteger(), () -> {
            throw new RuntimeException("model down");
        }), 200);
        assertEquals(List.of("d1", "d2", "d3"), ids(failing.rerank("any question", documents())));
    }

    @Test
    void aSingleCandidateIsReturnedWithoutAskingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        LlmReranker reranker = new LlmReranker(client(calls, () -> "1"), 200);

        List<Document> ranked = reranker.rerank("any question", documents().subList(0, 1));

        assertEquals(List.of("d1"), ids(ranked));
        assertEquals(0, calls.get(), "there is nothing to reorder, so the model must not be called");
    }

    private static List<Document> documents() {
        return List.of(
                Document.builder().id("d1").text("first passage").metadata(Map.of("filename", "a.md")).build(),
                Document.builder().id("d2").text("second passage").metadata(Map.of("filename", "b.md")).build(),
                Document.builder().id("d3").text("third passage").metadata(Map.of("filename", "c.md")).build());
    }

    private static List<String> ids(List<Document> documents) {
        return documents.stream().map(Document::getId).toList();
    }

    private static ChatClient client(AtomicInteger calls, Supplier<String> responder) {
        return (ChatClient) Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.class},
                (proxy, method, args) -> "prompt".equals(method.getName()) ? requestSpec(calls, responder) : null);
    }

    private static Object requestSpec(AtomicInteger calls, Supplier<String> responder) {
        return Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.ChatClientRequestSpec.class},
                (proxy, method, args) -> "call".equals(method.getName()) ? callSpec(calls, responder) : proxy);
    }

    private static Object callSpec(AtomicInteger calls, Supplier<String> responder) {
        return Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.CallResponseSpec.class},
                (proxy, method, args) -> {
                    if ("content".equals(method.getName())) {
                        calls.incrementAndGet();
                        return responder.get();
                    }
                    return null;
                });
    }
}
