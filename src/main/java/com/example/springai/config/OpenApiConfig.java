package com.example.springai.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc serves the descriptor from /v3/api-docs and the UI from /docs; the title is the only thing worth
 * customising, because the generated paths already describe the endpoints better than prose copied from the
 * controller would.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Spring AI Showcase",
        version = "1.1.8",
        description = "Chat, streaming, tool calling, structured output, RAG and vision endpoints. "
                + "The backend is configured with OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL / "
                + "OPENAI_EMBEDDING_MODEL, so the same endpoints run against Ollama or a hosted OpenAI account."))
public class OpenApiConfig {
}
