package com.example.springai.controller;

import com.example.springai.dto.RagQueryRequest;
import com.example.springai.dto.RagQueryResponse;
import com.example.springai.dto.RetrievedChunk;
import com.example.springai.guardrail.PiiRedactor;
import com.example.springai.guardrail.PromptInjectionDetector;
import com.example.springai.rag.RagCoverageGate;
import com.example.springai.service.RagDocumentIngestionService;
import com.example.springai.service.RagQueryResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/ai/rag")
@Tag(name = "RAG", description = "Grounded answers with citations, raw retrieval scores, and document upload")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private static final int EXCERPT_CHARS = 120;

    // A filename goes into a filter expression, so it is whitelisted rather than escaped: anything outside
    // this set is rejected instead of being interpreted as part of the expression.
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9._\\-]{1,120}");

    private static final long MAX_UPLOAD_BYTES = 512 * 1024;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RagDocumentIngestionService ingestionService;
    private final RagQueryResolver queryResolver;
    private final RagCoverageGate coverageGate;
    private final PiiRedactor piiRedactor;
    private final PromptInjectionDetector injectionDetector;
    private final boolean blockInjectionOnIngest;
    private final int defaultTopK;

    public RagController(ChatClient.Builder chatClientBuilder,
                         VectorStore vectorStore,
                         RagDocumentIngestionService ingestionService,
                         RagQueryResolver queryResolver,
                         RagCoverageGate coverageGate,
                         PiiRedactor piiRedactor,
                         PromptInjectionDetector injectionDetector,
                         @Value("${app.guardrails.block-injection-on-ingest:true}") boolean blockInjectionOnIngest,
                         @Value("${app.rag.top-k:4}") int defaultTopK,
                         @Value("${app.rag.similarity-threshold:0.5}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.ingestionService = ingestionService;
        this.queryResolver = queryResolver;
        this.coverageGate = coverageGate;
        this.piiRedactor = piiRedactor;
        this.injectionDetector = injectionDetector;
        this.blockInjectionOnIngest = blockInjectionOnIngest;
        this.defaultTopK = defaultTopK;
        this.chatClient = chatClientBuilder
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder()
                                .topK(defaultTopK)
                                .similarityThreshold(similarityThreshold)
                                .build())
                        .build())
                .build();
    }

    @PostMapping("/query")
    @Operation(summary = "Grounded answer with citations",
            description = "Retrieves context and answers with the chunks that reached the prompt. Grounding is decided before the model is asked: groundingOutcome is GROUNDED, RE_QUERIED, REFUSED or DISABLED, and REFUSED is built with no model call.")
    public RagQueryResponse queryKnowledgeBase(@Valid @RequestBody RagQueryRequest request) {
        long start = System.currentTimeMillis();

        String safeFilename = hasText(request.filename()) ? requireSafeFilename(request.filename()) : null;

        // Resolved before the advisor runs, because the advisor reads the user message text and then replaces it.
        RagQueryResolver.Resolution resolution = queryResolver.resolve(request.question(), request.conversationId());

        // Decide whether there is anything to answer from before the model is asked. When the gate says no it
        // is not called at all, so a question outside the knowledge base gets a refusal instead of an answer
        // invented from the model's own memory. An answerable question takes the exact path it always did.
        RagCoverageGate.Decision gate = coverageGate.assess(resolution.retrievalQuery(), request.question(), safeFilename);

        if (!gate.answerable()) {
            String refusal = piiRedactor.redact(refusalText(gate));
            List<RetrievedChunk> sources = chunksOfDocuments(gate.candidates());
            queryResolver.remember(request.conversationId(), request.question(), refusal);
            log.info("[CRAG] refused question={} searched=\"{}\" closest={}", request.question(), gate.query(),
                    sources.isEmpty() || sources.get(0).score() == null ? "none" : sources.get(0).score());
            return new RagQueryResponse(request.question(), resolution.retrievalQuery(), resolution.followUpResolved(),
                    resolution.outcome().name(), resolution.rewriteTimeMs(), refusal, sources,
                    gate.outcome().name(), System.currentTimeMillis() - start);
        }

        ChatClientResponse response = chatClient.prompt()
                .user(gate.query())
                .advisors(advisors -> {
                    if (safeFilename != null) {
                        advisors.param(QuestionAnswerAdvisor.FILTER_EXPRESSION,
                                "filename == '" + safeFilename + "'");
                    }
                })
                .call()
                .chatClientResponse();

        String answer = answerTextOf(response);
        // The advice is the exact text the model was given — a second search with different settings could
        // advertise chunks that never reached the prompt.
        List<RetrievedChunk> sources = chunksOf(response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS));
        List<String> pii = piiRedactor.findings(answer);
        String safeAnswer = piiRedactor.redact(answer);
        if (!pii.isEmpty()) {
            log.warn("[GUARDRAIL] redacted {} from a RAG answer for question={}", pii, request.question());
        }
        queryResolver.remember(request.conversationId(), request.question(), safeAnswer);

        return new RagQueryResponse(request.question(), resolution.retrievalQuery(), resolution.followUpResolved(),
                resolution.outcome().name(), resolution.rewriteTimeMs(), safeAnswer, sources,
                gate.outcome().name(), System.currentTimeMillis() - start);
    }

    /**
     * The refusal a question outside the knowledge base gets. It names what was searched and quotes the
     * closest passage whether or not it cleared any floor, because SESSION-HANDOFF records that a question
     * with no answer can score above a real match — so the score is evidence for the reader, not a gate.
     */
    private static String refusalText(RagCoverageGate.Decision gate) {
        StringBuilder refusal = new StringBuilder(
                "The knowledge base does not appear to cover this question, so I will not answer from memory.");
        List<Document> candidates = gate.candidates();
        if (!candidates.isEmpty()) {
            Document closest = candidates.get(0);
            Object filename = closest.getMetadata().getOrDefault("filename", "unknown");
            Double score = closest.getScore();
            refusal.append(" The closest passage was in ").append(filename);
            refusal.append(score == null
                    ? " (no similarity reported)"
                    : String.format(Locale.ROOT, " (cosine %.3f)", score));
            refusal.append(", which is not close enough to answer from.");
        }
        refusal.append(" Searched: \"").append(gate.query()).append("\".");
        return refusal.toString();
    }

    // An inspection endpoint: an empty list must not look like an empty store, so the reason for zero
    // matches is reported.
    @GetMapping("/search")
    @Operation(summary = "Raw retrieval scores",
            description = "The vector+BM25 ranking with each chunk's cosine and no similarity floor — use it to see the score the grounded path's threshold would reject.")
    public Map<String, Object> rawVectorSearch(
            @Parameter(description = "Search text", example = "how much is the home office stipend?")
            @RequestParam String query,
            @Parameter(description = "Max results (0 falls back to app.rag.top-k)", example = "3")
            @RequestParam(required = false, defaultValue = "0") int topK,
            @Parameter(description = "Restrict to one ingested document", example = "company-policy.md")
            @RequestParam(required = false) String filename) {

        SearchRequest.Builder search = SearchRequest.builder()
                .query(query)
                .topK(topK > 0 ? topK : defaultTopK)
                .similarityThresholdAll();
        if (hasText(filename)) {
            search.filterExpression(new FilterExpressionBuilder()
                    .eq("filename", requireSafeFilename(filename))
                    .build());
        }

        List<Document> documents = vectorStore.similaritySearch(search.build());

        List<Map<String, Object>> results = documents.stream()
                .map(doc -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", doc.getId());
                    row.put("score", doc.getScore());
                    row.put("content", doc.getText() != null ? doc.getText() : "");
                    row.put("metadata", doc.getMetadata());
                    return row;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("totalResults", results.size());
        response.put("results", results);
        if (results.isEmpty()) {
            response.put("note", "No stored chunk scored above 0.0 cosine for this query;"
                    + " the search floor cannot be lowered further because negative similarity is always discarded.");
        }
        return response;
    }

    /**
     * Adds a plain-text or Markdown document to the knowledge base. Re-uploading the same filename replaces
     * its previous chunks, so iterating on a document does not double its presence in every answer.
     */
    // Without the explicit consumes, springdoc documents this as application/json and Swagger UI renders a
    // JSON body editor for a part that only exists as multipart — "Try it out" cannot pick a file.
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document",
            description = "Indexes a text/Markdown file; re-uploading the same filename replaces its chunks. An upload matching prompt-injection rules is rejected when guardrails.block-injection-on-ingest is on.")
    public Map<String, Object> addDocument(
            @Parameter(description = "Text or Markdown file, up to 512 KB")
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file part is empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "document is larger than " + MAX_UPLOAD_BYTES / 1024 + " KB");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.startsWith("text/") && !contentType.contains("json")
                && !contentType.contains("xml") && !contentType.equals("application/octet-stream")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "only text documents are readable: " + contentType);
        }

        String filename = requireSafeFilename(hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : "upload-" + System.currentTimeMillis() + ".txt");

        // Screened before it can be indexed: a retrieved chunk is injected as context, so an uploaded
        // "ignore your instructions" line is an indirect prompt injection aimed at every later answer.
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "document could not be read: " + ex.getMessage());
        }
        List<String> injection = injectionDetector.scan(content);
        if (!injection.isEmpty()) {
            log.warn("[GUARDRAIL] rejected upload {} for injection patterns {}", filename, injection);
            if (blockInjectionOnIngest) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "document rejected: it matches prompt-injection rules " + injection);
            }
        }

        ingestionService.evict(filename);
        int chunks = ingestionService.ingest(file.getResource(), filename, "upload");
        log.info("[RAG-UPLOAD] Indexed {} chunks from uploaded document {}", chunks, filename);

        return Map.of("filename", filename, "chunks", chunks);
    }

    private static String requireSafeFilename(String filename) {
        String candidate = filename.contains("/") ? filename.substring(filename.lastIndexOf('/') + 1) : filename;
        candidate = candidate.contains("\\") ? candidate.substring(candidate.lastIndexOf('\\') + 1) : candidate;
        if (!SAFE_FILENAME.matcher(candidate).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "filename may only contain letters, digits, dot, underscore or dash: " + candidate);
        }
        return candidate;
    }

    private static String answerTextOf(ChatClientResponse response) {
        if (response.chatResponse() == null || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return "";
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        return text != null ? text : "";
    }

    private static List<RetrievedChunk> chunksOf(Object retrieved) {
        if (!(retrieved instanceof List<?> documents)) {
            return List.of();
        }
        return chunksOfDocuments(documents.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
                .toList());
    }

    private static List<RetrievedChunk> chunksOfDocuments(List<Document> documents) {
        if (documents == null) {
            return List.of();
        }
        return documents.stream()
                .map(doc -> new RetrievedChunk(
                        String.valueOf(doc.getMetadata().getOrDefault("filename", "unknown")),
                        doc.getScore(),
                        excerpt(doc.getText())))
                .toList();
    }

    private static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String collapsed = content.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= EXCERPT_CHARS ? collapsed : collapsed.substring(0, EXCERPT_CHARS) + "...";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
