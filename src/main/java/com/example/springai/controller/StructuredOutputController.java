package com.example.springai.controller;

import com.example.springai.dto.CodeReviewReport;
import com.example.springai.dto.MovieRecommendation;
import com.example.springai.error.StructuredOutputParseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.web.bind.annotation.*;

import java.util.function.Predicate;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/ai/structured")
@Tag(name = "Structured output", description = "Model output bound to a Java record, validated and retried once")
public class StructuredOutputController {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputController.class);

    // A small model copies the JSON Schema it is shown and fills the values in under "properties". The
    // flat answer shape is therefore restated after the schema, where it is the last thing the model reads.
    private static final String MOVIE_ANSWER_SHAPE = """
            Reply with one JSON object whose top-level keys are exactly title, releaseYear, director, genre, \
            imdbRating, summary, keyActors. Every key must carry a real, non-empty value. Never nest the \
            values under "properties", never repeat "$schema", "type", "items" or "required", and use no \
            markdown fences.""";

    private static final String REVIEW_ANSWER_SHAPE = """
            Reply with one JSON object whose top-level keys are exactly language, qualityScoreOutOf100, \
            detectedVulnerabilities, performanceTips, suggestedRefactoring, overallVerdict. Every key must \
            carry a real, non-empty value describing the supplied code. Never nest the values under \
            "properties", never repeat "$schema", "type", "items" or "required", and use no markdown fences.""";

    // Low, but not zero: greedy decoding on a small model can lock onto an empty field and repeat it,
    // which a retry with the same prompt would otherwise reproduce.
    private static final OpenAiChatOptions JSON_OPTIONS = OpenAiChatOptions.builder()
            .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
            .temperature(0.2)
            .build();

    private static final int MAX_OUTPUT_ATTEMPTS = 2;

    private final ChatClient chatClient;

    public StructuredOutputController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/movie")
    @Operation(summary = "Structured movie recommendation",
            description = "Returns a typed MovieRecommendation. A reply with blank required fields is retried once, then surfaces as a structured 422.")
    public MovieRecommendation recommendMovie(
            @Parameter(description = "Genre to recommend from", example = "Sci-Fi")
            @RequestParam(defaultValue = "Sci-Fi") String genre) {
        return validated(MovieRecommendation.class, () -> chatClient.prompt()
                .system("You are a film critic database. Reply with one JSON object and no other text.")
                .user(u -> u.text("""
                        Recommend a highly-acclaimed movie in the genre: {genre}.
                        {shape}""")
                        .param("genre", genre)
                        .param("shape", MOVIE_ANSWER_SHAPE))
                .options(JSON_OPTIONS)
                .call()
                .responseEntity(MovieRecommendation.class),
                movie -> !isBlank(movie.title()) && !isBlank(movie.director()) && !isBlank(movie.summary()));
    }

    @PostMapping("/code-review")
    @Operation(summary = "Structured code review",
            description = "POST the source text as the raw request body. Returns a typed CodeReviewReport.")
    public CodeReviewReport reviewCode(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Source code to review")
            @RequestBody String codeSnippet) {
        return validated(CodeReviewReport.class, () -> chatClient.prompt()
                .system("You are a Principal Software Security & Performance Architect. Reply with one JSON object and no other text.")
                .user(u -> u.text("""
                        Perform a structured code review on the following source code snippet:
                        ```
                        {code}
                        ```
                        {shape}""")
                        .param("code", codeSnippet)
                        .param("shape", REVIEW_ANSWER_SHAPE))
                .options(JSON_OPTIONS)
                .call()
                .responseEntity(CodeReviewReport.class),
                report -> !isBlank(report.language()) && !isBlank(report.overallVerdict())
                        && report.detectedVulnerabilities() != null);
    }

    /**
     * responseEntity() is used instead of entity() because entity() discards the completion it parsed: a
     * rejected answer then has no raw text to report, and fetching it separately would sample a second
     * completion (every accessor on the spec re-issues the request). The schema itself no longer needs to
     * be pasted into the prompt — the client appends it when a target type is supplied.
     */
    private <T> T validated(Class<T> targetType, Supplier<ResponseEntity<ChatResponse, T>> invocation,
                            Predicate<T> isComplete) {
        StructuredOutputParseException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_OUTPUT_ATTEMPTS; attempt++) {
            String modelOutput = null;
            try {
                ResponseEntity<ChatResponse, T> response = invocation.get();
                modelOutput = textOf(response.response());
                T value = response.entity();
                if (value != null && isComplete.test(value)) {
                    return value;
                }
                lastFailure = new StructuredOutputParseException(targetType, modelOutput);
            } catch (RuntimeException ex) {
                lastFailure = new StructuredOutputParseException(targetType, modelOutput, ex);
            }
            log.warn("Rejected model output for {} on attempt {}/{}: {}",
                    targetType.getSimpleName(), attempt, MAX_OUTPUT_ATTEMPTS, lastFailure.getMessage());
        }
        throw lastFailure;
    }

    private static String textOf(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
