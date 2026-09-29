package com.example.springai.integration;

import com.example.springai.controller.ChatController;
import com.example.springai.controller.StructuredOutputController;
import com.example.springai.controller.ToolCallingController;
import com.example.springai.dto.MovieRecommendation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControllerIntegrationTest {

    @Test
    void testChatControllerStandalone() throws Exception {
        ChatClient fakeChatClient = createFakeChatClient("Spring AI simplifies generative AI integration.");
        ChatController chatController = new ChatController(fakeChatClient);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(chatController).build();

        mockMvc.perform(get("/api/ai/chat")
                        .param("message", "What is Spring AI?")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("What is Spring AI?"))
                .andExpect(jsonPath("$.response").value("Spring AI simplifies generative AI integration."));
    }

    @Test
    void testStructuredOutputControllerStandalone() throws Exception {
        MovieRecommendation expected = new MovieRecommendation(
                "Blade Runner 2049", 2017, "Denis Villeneuve", "Sci-Fi", 8.0,
                "A futuristic neo-noir thriller.", List.of("Ryan Gosling", "Harrison Ford")
        );
        ChatClient fakeChatClient = createFakeChatClient(expected);
        StructuredOutputController controller = new StructuredOutputController(fakeChatClient);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/ai/structured/movie")
                        .param("genre", "Sci-Fi")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Blade Runner 2049"))
                .andExpect(jsonPath("$.releaseYear").value(2017))
                .andExpect(jsonPath("$.director").value("Denis Villeneuve"));
    }

    @Test
    void testToolCallingControllerStandalone() throws Exception {
        ChatClient fakeChatClient = createFakeChatClient("The weather in Tokyo is 18.5°C and partly cloudy.");
        ToolCallingController controller = new ToolCallingController(fakeChatClient);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/ai/tools/weather")
                        .param("prompt", "What is the weather in Tokyo?")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("What is the weather in Tokyo?"))
                .andExpect(jsonPath("$.response").value("The weather in Tokyo is 18.5°C and partly cloudy."));
    }

    private static ChatClient createFakeChatClient(Object responsePayload) {
        return (ChatClient) Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.class},
                (proxy, method, args) -> {
                    if ("prompt".equals(method.getName())) {
                        return createFakeRequestSpec(responsePayload);
                    }
                    return null;
                }
        );
    }

    private static Object createFakeRequestSpec(Object responsePayload) {
        return Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.ChatClientRequestSpec.class},
                (proxy, method, args) -> {
                    if ("call".equals(method.getName())) {
                        return createFakeCallResponseSpec(responsePayload);
                    }
                    // Return self for chain methods (user, system, functions, advisors, etc.)
                    return proxy;
                }
        );
    }

    private static Object createFakeCallResponseSpec(Object responsePayload) {
        return Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.CallResponseSpec.class},
                (proxy, method, args) -> {
                    if ("content".equals(method.getName())) {
                        return responsePayload instanceof String ? responsePayload : "";
                    }
                    if ("entity".equals(method.getName())) {
                        return responsePayload;
                    }
                    if ("chatResponse".equals(method.getName())) {
                        String text;
                        if (responsePayload instanceof String asString) {
                            text = asString;
                        } else {
                            try {
                                text = new ObjectMapper().writeValueAsString(responsePayload);
                            } catch (JsonProcessingException ex) {
                                throw new IllegalStateException(ex);
                            }
                        }
                        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
                    }
                    return null;
                }
        );
    }
}
