package com.example.springai.controller;

import com.example.springai.error.ImageDownloadException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/vision")
public class MultimodalController {

    // Many CDNs (Wikimedia included) reject headerless downloads with 403. UrlResource cannot send one.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private final ChatClient chatClient;
    private final RestClient restClient = RestClient.create();

    public MultimodalController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/analyze")
    public Map<String, String> analyzeImage(
            @RequestParam String imageUrl,
            @RequestParam(defaultValue = "Describe what you see in this image in detail.") String question) {

        URI target = publicHttpUri(imageUrl);

        ResponseEntity<byte[]> downloaded;
        try {
            downloaded = restClient.get()
                    .uri(target)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .accept(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.IMAGE_GIF, MediaType.APPLICATION_OCTET_STREAM)
                    .retrieve()
                    .toEntity(byte[].class);
        } catch (RuntimeException ex) {
            throw new ImageDownloadException(imageUrl, ex.getMessage(), ex);
        }

        byte[] imageBytes = downloaded.getBody();
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ImageDownloadException(imageUrl, "the download returned an empty body", null);
        }

        MimeType mimeType = downloaded.getHeaders().getContentType() != null
                ? downloaded.getHeaders().getContentType()
                : MimeTypeUtils.IMAGE_PNG;

        // Model failures are left to propagate so the advice can report the real status and cause.
        String response = chatClient.prompt()
                .user(u -> u.text(question).media(new Media(mimeType, new ByteArrayResource(imageBytes))))
                .call()
                .content();

        return Map.of(
                "imageUrl", imageUrl,
                "question", question,
                "mediaType", mimeType.toString(),
                "analysis", response != null ? response : ""
        );
    }

    /**
     * The server fetches the caller's URL, so an unchecked value would reach internal hosts and cloud
     * metadata endpoints. Only public http(s) addresses are allowed.
     */
    private static URI publicHttpUri(String imageUrl) {
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (IllegalArgumentException ex) {
            throw new ImageDownloadException(imageUrl, "malformed URL", ex);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new ImageDownloadException(imageUrl, "only absolute http(s) image URLs are supported", null);
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || isUniqueLocalIpv6(address)) {
                    throw new ImageDownloadException(imageUrl,
                            "refused to fetch from a private address: " + address.getHostAddress(), null);
                }
            }
        } catch (UnknownHostException ex) {
            throw new ImageDownloadException(imageUrl, "host could not be resolved", ex);
        }
        return uri;
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        return address instanceof Inet6Address
                && (address.getAddress()[0] & 0xFE) == 0xFC;
    }
}
