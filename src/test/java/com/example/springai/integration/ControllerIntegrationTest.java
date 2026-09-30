package com.example.springai.integration;

import com.example.springai.controller.ChatController;
import com.example.springai.controller.RagController;
import com.example.springai.controller.StructuredOutputController;
import com.example.springai.controller.ToolCallingController;
import com.example.springai.dto.MovieRecommendation;
import com.example.springai.error.GlobalExceptionHandler;
import com.example.springai.rag.StubEmbeddingModel;
import com.example.springai.service.RagDocumentIngestionService;
import com.example.springai.service.RagStorePersistence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControllerIntegrationTest {

    @Test
    void testChatControllerStandalone() throws Exception {
        ChatClient fakeChatClient = createFakeChatClient("Spring AI simplifies generative AI integration.");
        ChatController chatController = new ChatController(fakeChatClient, fakeChatClient, inMemoryChatMemory());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(chatController).build();

        mockMvc.perform(get("/api/ai/chat")
                        .param("message", "What is Spring AI?")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("What is Spring AI?"))
                .andExpect(jsonPath("$.response").value("Spring AI simplifies generative AI integration."));
    }

    @Test
    void testConversationIdLongerThanTheSchemaColumnIsRejected() throws Exception {
        ChatClient fakeChatClient = createFakeChatClient("unused");
        ChatController controller = new ChatController(fakeChatClient, fakeChatClient, inMemoryChatMemory());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // VARCHAR(36) in the chat memory schema: without this the failure surfaces as a JDBC insert error.
        mockMvc.perform(get("/api/ai/chat/memory")
                        .param("message", "hi")
                        .param("conversationId", "this-conversation-identifier-is-definitely-longer-than-thirty-six-characters"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request_rejected"));
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

    // Provenance is read from what the advisor actually retrieved, never from a second search.
    @Test
    void testRagQueryReportsTheChunksTheAdvisorRetrieved() throws Exception {
        Document chunk = Document.builder()
                .id("chunk-1")
                .text("Employees receive 25 days of annual leave.")
                .metadata(Map.of("filename", "policy.md"))
                .score(0.42)
                .build();
        ChatClient fakeChatClient = createFakeChatClient("You get 25 days.", Map.of(
                QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of(chunk)));
        SimpleVectorStore vectorStore = stubbedVectorStore();

        RagController controller = new RagController(createFakeBuilder(fakeChatClient), vectorStore,
                ingestionService(vectorStore), 4, 0.2);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/ai/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How many leave days?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("You get 25 days."))
                .andExpect(jsonPath("$.sourceDocuments[0].filename").value("policy.md"))
                .andExpect(jsonPath("$.sourceDocuments[0].score").value(0.42));
    }

    @Test
    void testRagQueryRejectsABlankQuestion() throws Exception {
        SimpleVectorStore vectorStore = stubbedVectorStore();
        RagController controller = new RagController(createFakeBuilder(createFakeChatClient("x", Map.of())),
                vectorStore, ingestionService(vectorStore), 4, 0.2);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // Before validation this reached Spring AI as a null query and came back as a message-less 500.
        mockMvc.perform(post("/api/ai/rag/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void testRagUploadRejectsAFileNameThatCouldBreakAFilterExpression() throws Exception {
        SimpleVectorStore vectorStore = stubbedVectorStore();
        RagController controller = new RagController(createFakeBuilder(createFakeChatClient("x", Map.of())),
                vectorStore, ingestionService(vectorStore), 4, 0.2);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MockMultipartFile hostile = new MockMultipartFile("file", "policy' || 1==1 || '.md",
                "text/markdown", "# Policy".getBytes());

        mockMvc.perform(multipart("/api/ai/rag/documents").file(hostile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("request_rejected"));
    }

    @Test
    void testRagUploadIndexesAPlaintextDocument() throws Exception {
        SimpleVectorStore vectorStore = stubbedVectorStore();
        RagController controller = new RagController(createFakeBuilder(createFakeChatClient("x", Map.of())),
                vectorStore, ingestionService(vectorStore), 4, 0.2);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MockMultipartFile document = new MockMultipartFile("file", "runbook.md", "text/markdown",
                ("# On-call runbook\n\nRestart the gateway before draining traffic.\n\n"
                        + "## Escalation\n\nPage the secondary owner after fifteen minutes.").getBytes());

        mockMvc.perform(multipart("/api/ai/rag/documents").file(document))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("runbook.md"))
                .andExpect(jsonPath("$.chunks").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    private static ChatMemory inMemoryChatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(6)
                .build();
    }

    private static ChatClient createFakeChatClient(Object responsePayload) {
        return createFakeChatClient(responsePayload, Map.of());
    }

    private static ChatClient createFakeChatClient(Object responsePayload, Map<String, Object> responseContext) {
        return (ChatClient) Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.class},
                (proxy, method, args) -> "prompt".equals(method.getName())
                        ? createFakeRequestSpec(responsePayload, responseContext)
                        : null
        );
    }

    private static ChatClient.Builder createFakeBuilder(ChatClient chatClient) {
        return (ChatClient.Builder) Proxy.newProxyInstance(
                ChatClient.Builder.class.getClassLoader(),
                new Class<?>[]{ChatClient.Builder.class},
                (proxy, method, args) -> "build".equals(method.getName()) ? chatClient : proxy
        );
    }

    // A real store over the offline stub embedder: uploads are indexed for real, so this exercises the
    // splitter and the store rather than a mock of them. Persistence is off, because writing a snapshot from
    // a controller test would assert SimpleVectorStore's file format twice.
    private static SimpleVectorStore stubbedVectorStore() {
        return SimpleVectorStore.builder(new StubEmbeddingModel()).build();
    }

    private static RagDocumentIngestionService ingestionService(SimpleVectorStore vectorStore) {
        return new RagDocumentIngestionService(vectorStore,
                new RagStorePersistence(vectorStore, false, "target/rag-store-never-written", false));
    }

    private static Object createFakeRequestSpec(Object responsePayload, Map<String, Object> responseContext) {
        return Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.ChatClientRequestSpec.class},
                (proxy, method, args) -> "call".equals(method.getName())
                        ? createFakeCallResponseSpec(responsePayload, responseContext)
                        // Return self for chain methods (user, system, tools, advisors, etc.)
                        : proxy
        );
    }

    private static Object createFakeCallResponseSpec(Object responsePayload, Map<String, Object> responseContext) {
        return Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class<?>[]{ChatClient.CallResponseSpec.class},
                (proxy, method, args) -> {
                    if ("content".equals(method.getName())) {
                        return responsePayload instanceof String ? responsePayload : "";
                    }
                    if ("chatResponse".equals(method.getName())) {
                        return toChatResponse(responsePayload);
                    }
                    if ("responseEntity".equals(method.getName())) {
                        return new ResponseEntity<>(toChatResponse(responsePayload), responsePayload);
                    }
                    if ("chatClientResponse".equals(method.getName())) {
                        return new ChatClientResponse(toChatResponse(responsePayload), responseContext);
                    }
                    return null;
                }
        );
    }

    // The structured and RAG endpoints read the raw completion as well as the converted result, because a
    // rejected answer is reported with the text that produced it.
    private static ChatResponse toChatResponse(Object responsePayload) {
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
}
