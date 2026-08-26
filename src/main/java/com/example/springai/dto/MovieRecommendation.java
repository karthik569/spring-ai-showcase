package com.example.springai.dto;

import java.util.List;

public record MovieRecommendation(
        String title,
        int releaseYear,
        String director,
        String genre,
        double imdbRating,
        String summary,
        List<String> keyActors
) {}
