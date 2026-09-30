package com.example.springai.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

/**
 * Writes one token-usage line per completion.
 * <p>
 * Spring AI already counts tokens into the {@code gen_ai.client.token.usage} counter, but a counter cannot
 * answer what a specific request cost; this line carries the same MDC requestId as logs/endpoints.log, so a
 * slow call can be attributed to the prompt size that caused it.
 */
public class TokenUsageAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger("TOKEN_USAGE");

    // Spring AI appends its own terminal advisor (name "call", which invokes the model) to the request's
    // advisor list at order Ordered.LOWEST_PRECEDENCE. Ties are broken by insertion order, and the terminal
    // is inserted last, so an advisor that also claims LOWEST_PRECEDENCE sorts behind it and is never
    // popped off the chain. Staying one step ahead of that value is what makes this advisor run.
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    @Override
    public String getName() {
        return "TokenUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Usage usage = usageOf(response);
        Integer total = usage == null ? null : usage.getTotalTokens();
        // A streamed call reaches this on the terminal chunk, which carries no counters for Ollama; logging
        // it would write "tokens prompt=0 completion=0 total=0" and hide the calls that did report usage.
        if (total != null && total != 0) {
            log.info("tokens prompt={} completion={} total={}",
                    usage.getPromptTokens(), usage.getCompletionTokens(), total);
        }
        return response;
    }

    private static Usage usageOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getMetadata() == null) {
            return null;
        }
        return response.chatResponse().getMetadata().getUsage();
    }
}
