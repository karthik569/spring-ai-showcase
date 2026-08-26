package com.example.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.Media;
import org.springframework.core.io.UrlResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/vision")
public class MultimodalController {

    private final ChatClient chatClient;

    public MultimodalController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/analyze")
    public Map<String, String> analyzeImage(
            @RequestParam(defaultValue = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/280px-PNG_transparency_demonstration_1.png") String imageUrl,
            @RequestParam(defaultValue = "Describe what you see in this image in detail.") String question) {

        try {
            UrlResource imageResource = new UrlResource(URI.create(imageUrl));
            Media imageMedia = new Media(MimeTypeUtils.IMAGE_PNG, imageResource);

            String response = chatClient.prompt()
                    .user(u -> u.text(question).media(imageMedia))
                    .call()
                    .content();

            return Map.of(
                    "imageUrl", imageUrl,
                    "question", question,
                    "analysis", response != null ? response : ""
            );
        } catch (Exception e) {
            return Map.of(
                    "imageUrl", imageUrl,
                    "error", e.getMessage()
            );
        }
    }
}
