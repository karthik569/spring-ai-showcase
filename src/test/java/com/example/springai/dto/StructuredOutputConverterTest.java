package com.example.springai.dto;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Offline regression for the structured-output parse path: the converter's tolerance for the shapes a
 * small local model actually emits (fences, prose, schema echo) is what the controller's 422 validation
 * relies on. If a Spring AI upgrade changes that tolerance, these rows say so before a user does.
 */
class StructuredOutputConverterTest {

    private final BeanOutputConverter<MovieRecommendation> converter =
            new BeanOutputConverter<>(MovieRecommendation.class);

    private static final String CLEAN_JSON = """
            {"title":"Blade Runner","releaseYear":1982,"director":"Ridley Scott","genre":"Sci-Fi",
             "imdbRating":8.1,"summary":"A hunter of rogue androids questions his own humanity.",
             "keyActors":["Harrison Ford","Rutger Hauer"]}""";

    @Test
    void parsesCleanJson() {
        MovieRecommendation movie = converter.convert(CLEAN_JSON);

        assertEquals("Blade Runner", movie.title());
        assertEquals(1982, movie.releaseYear());
        assertEquals(2, movie.keyActors().size());
    }

    @Test
    void parsesJsonInsideMarkdownFences() {
        MovieRecommendation movie = converter.convert("```json\n" + CLEAN_JSON + "\n```");

        assertEquals("Blade Runner", movie.title());
    }

    @Test
    void rejectsOutputWithNoJsonObject() {
        assertThrows(RuntimeException.class,
                () -> converter.convert("I recommend Blade Runner, directed by Ridley Scott."));
    }

    /**
     * The documented 0.5B failure mode: the model echoes the JSON Schema and nests values under
     * "properties". Mapping must leave the fields empty so the controller's completeness check rejects
     * the answer instead of shipping a null-titled recommendation.
     */
    @Test
    void schemaEchoLeavesFieldsEmptyForTheValidatorToReject() {
        String schemaEcho = """
                {"type":"object","properties":{"title":"Blade Runner","releaseYear":1982,
                 "director":"Ridley Scott","genre":"Sci-Fi","imdbRating":8.1,
                 "summary":"A hunter of rogue androids.","keyActors":["Harrison Ford"]},
                 "required":["title"]}""";

        MovieRecommendation movie = converter.convert(schemaEcho);

        assertNull(movie.title(), "schema-shaped output must not fill the record");
        assertNull(movie.director(), "schema-shaped output must not fill the record");
    }
}
