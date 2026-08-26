package com.example.springai.controller;

import com.example.springai.dto.CodeReviewReport;
import com.example.springai.dto.MovieRecommendation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/structured")
public class StructuredOutputController {

    private final ChatClient chatClient;

    public StructuredOutputController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/movie")
    public MovieRecommendation recommendMovie(@RequestParam(defaultValue = "Sci-Fi") String genre) {
        return chatClient.prompt()
                .system("You are a film critic database.")
                .user(u -> u.text("Recommend a highly-acclaimed movie in the genre: {genre}").param("genre", genre))
                .call()
                .entity(MovieRecommendation.class);
    }

    @PostMapping("/code-review")
    public CodeReviewReport reviewCode(@RequestBody String codeSnippet) {
        return chatClient.prompt()
                .system("You are a Principal Software Security & Performance Architect.")
                .user(u -> u.text("Perform a structured code review on the following source code snippet:\n```\n{code}\n```")
                        .param("code", codeSnippet))
                .call()
                .entity(CodeReviewReport.class);
    }
}
