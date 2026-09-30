package com.example.springai.support;

import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ChatClient} that answers with a fixed string and records every user text it was handed.
 *
 * <p>The recording is the point: a rewrite that ignores chat memory and a rewrite that used it look identical
 * in the response, so the only way to assert that conversation state actually reached the model is to catch the
 * prompt. Built on {@code Proxy} because {@code ChatClient} has no implementation to subclass and the chain
 * methods all return the spec itself.
 */
public final class RecordingChatClient {

    private final ChatClient client;
    private final List<String> prompts = new ArrayList<>();

    private RecordingChatClient(String output) {
        this.client = build(output);
    }

    public static RecordingChatClient returning(String output) {
        return new RecordingChatClient(output);
    }

    public ChatClient client() {
        return client;
    }

    public List<String> prompts() {
        return prompts;
    }

    public String lastPrompt() {
        if (prompts.isEmpty()) {
            throw new AssertionError("the rewriter was never called");
        }
        return prompts.get(prompts.size() - 1);
    }

    private ChatClient build(String output) {
        return (ChatClient) Proxy.newProxyInstance(ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.class}, (proxy, method, args) -> "prompt".equals(method.getName())
                        ? Proxy.newProxyInstance(ChatClient.class.getClassLoader(),
                        new Class<?>[]{ChatClient.ChatClientRequestSpec.class}, (spec, specMethod, specArgs) -> {
                            if ("user".equals(specMethod.getName()) && specArgs.length == 1
                                    && specArgs[0] instanceof String text) {
                                prompts.add(text);
                            }
                            return "call".equals(specMethod.getName())
                                    ? Proxy.newProxyInstance(ChatClient.class.getClassLoader(),
                                    new Class<?>[]{ChatClient.CallResponseSpec.class}, (call, callMethod, callArgs) ->
                                            "content".equals(callMethod.getName()) ? output : null)
                                    : spec;
                        })
                        : null);
    }
}
