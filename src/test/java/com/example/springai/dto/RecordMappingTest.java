package com.example.springai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testMovieRecommendationSerialization() throws Exception {
        MovieRecommendation movie = new MovieRecommendation(
                "Inception",
                2010,
                "Christopher Nolan",
                "Sci-Fi",
                8.8,
                "A thief enters the dreams of others.",
                List.of("Leonardo DiCaprio", "Joseph Gordon-Levitt")
        );

        String json = objectMapper.writeValueAsString(movie);
        assertNotNull(json);
        assertTrue(json.contains("Inception"));
        assertTrue(json.contains("Christopher Nolan"));

        MovieRecommendation deserialized = objectMapper.readValue(json, MovieRecommendation.class);
        assertEquals(movie.title(), deserialized.title());
        assertEquals(movie.releaseYear(), deserialized.releaseYear());
        assertEquals(movie.keyActors().size(), deserialized.keyActors().size());
    }

    @Test
    void testCodeReviewReportSerialization() throws Exception {
        CodeReviewReport report = new CodeReviewReport(
                "Java",
                85,
                List.of("Potential NPE"),
                List.of("Use Optional or null check"),
                "if (x != null) { ... }",
                "PASS - Minor Improvements"
        );

        String json = objectMapper.writeValueAsString(report);
        assertNotNull(json);
        assertTrue(json.contains("Potential NPE"));

        CodeReviewReport deserialized = objectMapper.readValue(json, CodeReviewReport.class);
        assertEquals(report.language(), deserialized.language());
        assertEquals(report.qualityScoreOutOf100(), deserialized.qualityScoreOutOf100());
        assertEquals(report.overallVerdict(), deserialized.overallVerdict());
    }

    @Test
    void testRagQueryDtoSerialization() throws Exception {
        RagQueryResponse ragResponse = new RagQueryResponse(
                "What is the PTO policy?",
                "25 days annual leave.",
                List.of(new RetrievedChunk("company-policy.md", 0.61, "25 days annual leave")),
                120L
        );

        String json = objectMapper.writeValueAsString(ragResponse);
        assertNotNull(json);
        assertTrue(json.contains("25 days annual leave"));

        RagQueryResponse deserialized = objectMapper.readValue(json, RagQueryResponse.class);
        assertEquals(ragResponse.question(), deserialized.question());
        assertEquals(ragResponse.answer(), deserialized.answer());
        assertEquals(ragResponse.responseTimeMs(), deserialized.responseTimeMs());
    }
}
