package com.example.springai.dto;

import java.util.List;

public record CodeReviewReport(
        String language,
        int qualityScoreOutOf100,
        List<String> detectedVulnerabilities,
        List<String> performanceTips,
        String suggestedRefactoring,
        String overallVerdict
) {}
