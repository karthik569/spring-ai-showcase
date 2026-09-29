package com.example.springai.controller;

import com.example.springai.dto.CodeReviewReport;
import com.example.springai.dto.MovieRecommendation;
import com.example.springai.error.StructuredOutputParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.web.bind.annotation.*;

import java.util.function.Predicate;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/ai/structured")
public class StructuredOutputController {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputController.class);

    private static final BeanOutputConverter<MovieRecommendation> MOVIE_CONVERTER = new BeanOutputConverter<>(MovieRecommendation.class);
    private static final BeanOutputConverter<CodeReviewReport> REVIEW_CONVERTER = new BeanOutputConverter<>(CodeReviewReport.class);

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
            .withResponseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
            .withTemperature(0.2)
            .build();

    private static final int MAX_OUTPUT_ATTEMPTS = 2;

    private final ChatClient chatClient;

    public StructuredOutputController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/movie")
    public MovieRecommendation recommendMovie(@RequestParam(defaultValue = "Sci-Fi") String genre) {
        return validated(MovieRecommendation.class, MOVIE_CONVERTER, () -> chatClient.prompt()
                .system("You are a film critic database. Reply with one JSON object and no other text.")
                .user(u -> u.text("""
                        Recommend a highly-acclaimed movie in the genre: {genre}.
                        {format}
                        {shape}""")
                        .param("genre", genre)
                        .param("format", MOVIE_CONVERTER.getFormat())
                        .param("shape", MOVIE_ANSWER_SHAPE))
                .options(JSON_OPTIONS)
                .call()
                .chatResponse(),
                movie -> !isBlank(movie.title()) && !isBlank(movie.director()) && !isBlank(movie.summary()));
    }

    @PostMapping("/code-review")
    public CodeReviewReport reviewCode(@RequestBody String codeSnippet) {
        return validated(CodeReviewReport.class, REVIEW_CONVERTER, () -> chatClient.prompt()
                .system("You are a Principal Software Security & Performance Architect. Reply with one JSON object and no other text.")
                .user(u -> u.text("""
                        Perform a structured code review on the following source code snippet:
                        ```
                        {code}
                        ```
                        {format}
                        {shape}""")
                        .param("code", codeSnippet)
                        .param("format", REVIEW_CONVERTER.getFormat())
                        .param("shape", REVIEW_ANSWER_SHAPE))
                .options(JSON_OPTIONS)
                .call()
                .chatResponse(),
                report -> !isBlank(report.language()) && !isBlank(report.overallVerdict())
                        && report.detectedVulnerabilities() != null);
    }

    /**
     * The response is read once and converted here rather than through {@code CallResponseSpec.entity(Class)},
     * because in 1.0.0-M4 every accessor on that spec re-issues the request: pairing entity() with a later
     * chatResponse() would sample a second completion and report text that was never parsed.
     */
    private <T> T validated(Class<T> targetType, BeanOutputConverter<T> converter,
                            Supplier<ChatResponse> invocation, Predicate<T> isComplete) {
        StructuredOutputParseException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_OUTPUT_ATTEMPTS; attempt++) {
            String modelOutput = textOf(invocation.get());
            T value;
            try {
                value = converter.convert(modelOutput);
            } catch (RuntimeException ex) {
                value = null;
                lastFailure = new StructuredOutputParseException(targetType, modelOutput, ex);
            }
            if (value != null && isComplete.test(value)) {
                return value;
            }
            if (lastFailure == null) {
                lastFailure = new StructuredOutputParseException(targetType, modelOutput);
            }
            log.warn("Rejected model output for {} on attempt {}/{}: {}",
                    targetType.getSimpleName(), attempt, MAX_OUTPUT_ATTEMPTS, lastFailure.getMessage());
        }
        throw lastFailure;
    }

    // The schema travels as a prompt parameter, never as template text: StringTemplate uses braces as
    // delimiters, so inlining a JSON schema into the prompt text would be parsed as variable references.
    private static String textOf(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getContent();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
