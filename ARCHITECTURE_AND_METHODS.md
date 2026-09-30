# Spring AI — Architecture & Method Guide 🧠

A method-by-method read of `spring-ai-showcase`: what each class does, why it is written that way, and which
behaviours were verified against the running system rather than taken from documentation.

**Stack:** Java 21 · Spring Boot 3.5.16 · Spring AI **1.1.8** (GA, Maven Central — no milestone repository) ·
springdoc-openapi 2.8.17 · H2 (chat memory) · backend = any OpenAI-protocol server; all measurements below
were taken against Ollama 0.20.7 with `qwen2.5:0.5b-instruct` (chat) + `all-minilm` (384-dim embeddings).

---

## 1. Request lifecycle

```
 HTTP
  │
  ├─ EndpointLoggingFilter        HIGHEST_PRECEDENCE      requestId → MDC, one line per call
  ├─ AiConcurrencyLimitFilter     HIGHEST_PRECEDENCE+100  ≤4 model calls, else 503 + Retry-After
  │
  ▼
 @RestController (/api/ai/**)
  │   ChatClient ── built once per bean, never per request
  ▼
 Advisor chain (sorted by OrderComparator, stable)
  │   TokenUsageAdvisor              order = LOWEST_PRECEDENCE - 1
  │   MessageChatMemoryAdvisor       (conversational client only)
  │   QuestionAnswerAdvisor          (RAG client only)
  │   ChatModelCallAdvisor / ChatModelStreamAdvisor   ← appended by Spring AI, order = MAX_VALUE
  ▼
 ChatModel (OpenAI starter) ──► RestClient / WebClient ──► model backend
  ▲
  └── any failure surfaces in GlobalExceptionHandler, which turns it into a typed problem body
```

Static assets (`/`), Swagger (`/docs`, `/swagger-ui`, `/v3/api-docs`), `/actuator/**` and `/webjars` are
skipped by the concurrency filter (they are not `/api/ai/**`) and by the logging filter, so neither the demo
page's asset traffic nor health polling consumes model permits or pollutes `endpoints.log`.

---

## 2. The advisor chain: the ordering trap that silently disabled token logging

This is the one non-obvious mechanic in the app, learned from bytecode after the docs failed to explain it.

- `ChatClientCustomizer.customize(ChatClient.Builder)` is applied by `ChatClientBuilderConfigurer.configure()`
  to every prototype `ChatClient.Builder` the container hands out, so advice registered there reaches clients
  constructed inside controllers.
- `ChatClientRequestSpec.advisors(...)` **accumulates** (`List.addAll`) — `defaultAdvisors(...)` on a builder
  does not replace earlier advisors.
- `DefaultChatClientRequestSpec.buildAdvisorChain()` **appends** `ChatModelCallAdvisor` (name `"call"`, the
  advisor that actually invokes the model) and `ChatModelStreamAdvisor` to the request's advisor list, then
  sorts with `OrderComparator.sort` — a **stable** sort.
- `ChatModelCallAdvisor.getOrder()` returns `Ordered.LOWEST_PRECEDENCE` (`2147483647`), and it is inserted
  last. An advisor that also claims `LOWEST_PRECEDENCE` therefore ties with it and loses on insertion order:
  it sorts **behind** the terminal advisor, is never popped off the chain, and its `before`/`after` never run.
  No error is logged; the advisor simply does not exist at runtime.

`TokenUsageAdvisor` uses `Ordered.LOWEST_PRECEDENCE - 1` to stay one step ahead of the terminal advisor.
Confirmed at runtime: `TOKEN_USAGE` lines appear with the request's `requestId`, and
`/actuator/metrics/gen_ai.client.token.usage` counts the same calls.

`BaseAdvisor` covers both paths: `adviseCall` runs `before → chain.nextCall → after`, while `adviseStream`
filters on `AdvisorUtils.onFinishReason()` so `after` runs once, on the terminal chunk. For Ollama that final
chunk carries **no** usage counters, which is why the advisor skips zero totals instead of writing
`tokens prompt=0 completion=0 total=0` and burying the calls that did report usage.

---

## 3. Configuration layer

### [`AiConfig.java`](src/main/java/com/example/springai/config/AiConfig.java)

| Method | Behaviour | Why |
| :--- | :--- | :--- |
| `chatMemory(ChatMemoryRepository)` | `MessageWindowChatMemory` bounded to **6** messages over the auto-configured JDBC repository. | A window, not the whole transcript: the local model's context is small enough that unbounded history pushes the actual question out of the prompt. The repository owns its schema; this bean only bounds the window. |
| `tokenUsageAdvisorCustomizer()` | `ChatClientCustomizer` adding `TokenUsageAdvisor` to every builder. | Controllers build clients in their constructors (RAG needs a store-specific advisor); a customizer means each one does not re-declare token accounting. |
| `chatClient(ChatClient.Builder)` | Stateless client with the shared system prompt. | Tools, structured output and vision have no conversation. Since Spring AI **1.1.6** `MessageChatMemoryAdvisor` *rejects* a call with no conversation id, so a memory-carrying client could not serve them even if it were desirable. |
| `conversationalChatClient(ChatClient.Builder, ChatMemory)` | `@Qualifier("conversationalChatClient")`; adds `MessageChatMemoryAdvisor`. | Serves `/chat/memory`, `/chat/stream`. Every call must supply `ChatMemory.CONVERSATION_ID` — that parameter is what keeps two callers' histories apart. |
| `vectorStore(EmbeddingModel)` | `SimpleVectorStore` — in-memory, cosine, `all-minilm` vectors. | Zero-infrastructure RAG. It is **not** persisted: content is re-ingested from `classpath:/docs/*.md` at boot, and anything uploaded through `POST /rag/documents` is lost on restart (deliberate; the alternative is a real vector database). |

### [`EndpointLoggingFilter.java`](src/main/java/com/example/springai/config/EndpointLoggingFilter.java)

`OncePerRequestFilter` at `HIGHEST_PRECEDENCE`. Generates or adopts `X-Request-Id`, puts it in MDC and echoes
it on the response; logs method, path + query, status, latency, client IP and truncated bodies
(2000 chars); catches `ServletException|IOException|RuntimeException`, logs `FAILED <class>: <root message>`
and rethrows; `copyBodyToResponse()` in `finally`.

Three decisions to keep intact:

1. **SSE bypass.** `/stream` and `Accept: text/event-stream` skip `ContentCachingResponseWrapper`; buffering
   would stall every token until the stream closes.
2. **`shouldNotFilterErrorDispatch() = true`** so a 500 logs one line, not one per dispatch. Consequence: the
   container-rendered error body is not captured (`resp=<empty>`) — the `FAILED` clause carries the cause.
3. **Headers are never logged**, so an API key cannot reach `endpoints.log` or `app.log`.

### [`AiConcurrencyLimitFilter.java`](src/main/java/com/example/springai/config/AiConcurrencyLimitFilter.java)

`Semaphore` sized by `app.ai.max-concurrent-requests` (4), `tryAcquire()` without waiting; a refusal is
`503` + `Retry-After: 2` and never reaches the chain. Exposes the `app.ai.inflight` gauge.

The interesting part is **release timing**. `chain.doFilter(...)` returns as soon as the controller hands a
`Flux` to Spring MVC — the model is still generating on another thread. Releasing in the `finally` block would
free the permit mid-stream, so the filter checks `request.isAsyncStarted()` and, when true, attaches the
release to `AsyncContext` completion/error/timeout instead. If the stream already finished between those two
lines, `getAsyncContext()` throws `IllegalStateException` and the permit is released directly.

### [`OpenApiConfig.java`](src/main/java/com/example/springai/config/OpenApiConfig.java)

Sets the document title, version and a description naming the environment variables a caller must set — the
API is useless without knowing which model is wired in.

---

## 4. Controller layer

### [`ChatController.java`](src/main/java/com/example/springai/controller/ChatController.java)

| Method | Route | Notes |
| :--- | :--- | :--- |
| `simpleChat` | `GET /api/ai/chat` | `.call().content()` on the stateless client. |
| `templateChat` | `GET /api/ai/chat/template` | `{role}` / `{topic}` template variables on both system and user text. |
| `streamChat` | `GET /api/ai/chat/stream` | `Flux<String>` as `text/event-stream`; memory-aware via `conversationId`. |
| `chatWithMemory` | `GET /api/ai/chat/memory` | Conversational client + `ChatMemory.CONVERSATION_ID` advisor param. |
| `conversationHistory` | `GET /api/ai/chat/memory/history` | Reads `ChatMemory.get(id)` — shows what the advisor will inject, not what the model said. |
| `clearConversation` | `DELETE /api/ai/chat/memory` | `ChatMemory.clear(id)`. |

`requireConversationId` rejects blanks and lengths above **36 characters** at the boundary. The JDBC schema
stores the id in `VARCHAR(36)`, so a longer value fails on insert deep inside `JdbcTemplate` and arrives as an
opaque 500.

### [`RagController.java`](src/main/java/com/example/springai/controller/RagController.java)

| Method | Route | Notes |
| :--- | :--- | :--- |
| `queryKnowledgeBase` | `POST /api/ai/rag/query` | `QuestionAnswerAdvisor` retrieves, the model answers, and citations come from `response.context().get(RETRIEVED_DOCUMENTS)` — literally the chunks that reached the prompt. `filename` maps to the advisor's `FILTER_EXPRESSION` parameter. |
| `rawVectorSearch` | `GET /api/ai/rag/search` | `similarityThresholdAll()` plus an optional `FilterExpressionBuilder().eq("filename", …)`. Surfaces `Document.getScore()`. When nothing matches it returns `totalResults: 0` **and** a `note`, because an inspection endpoint that returns a bare empty list cannot be told apart from an empty store. |
| `addDocument` | `POST /api/ai/rag/documents` | `multipart/form-data`, ≤512 KB per file, `evict(filename)` before `ingest(...)` so re-uploading replaces instead of duplicating. |

Two guards are load-bearing:

- **Filename whitelist.** A filename is interpolated into a filter expression, so `[A-Za-z0-9._\-]{1,120}` is
  enforced instead of escaping — anything else is rejected before the expression is built.
- **Explicit `consumes = MULTIPART_FORM_DATA_VALUE`.** Without it springdoc documents the part as
  `application/json`, and Swagger UI renders a JSON body editor for a part that only exists as multipart, so
  "Try it out" cannot pick a file.

Threshold reasoning, because it looks arbitrary: `all-minilm` cosine for a relevant short question against
these chunks lands around 0.3–0.45. At the 0.5 default the advisor silently drops the context and the model
answers from memory — the endpoint still returns a fluent sentence, with `sourceDocuments` proving the chunk
was found and never used. Hence `app.rag.similarity-threshold: 0.2`.

### [`ToolCallingController.java`](src/main/java/com/example/springai/controller/ToolCallingController.java)

`GET /tools/weather`, `/tools/order`, `/tools/multi` bind tools **by bean name** with
`.toolNames("getCurrentWeather")` / `("getOrderStatus")`, at temperature 0.2 for the single-tool endpoints.
`answered(...)` logs a warning when the model returns no completion, so an empty answer is not mistaken for a
successful call.

Temperature 0.2 is empirical, not decorative: on `qwen2.5:0.5b-instruct`, 0.2 called both tools in most runs,
**0.0 stopped calling them**, and an "answer only from the tool result" system prompt made the model describe
the tool instead of using it. Forcing a tool call is deliberately not attempted — the showcase point is
model-dispatched selection.

### [`StructuredOutputController.java`](src/main/java/com/example/springai/controller/StructuredOutputController.java)

`GET /structured/movie` → `MovieRecommendation`; `POST /structured/code-review` → `CodeReviewReport`. Both go
through `validated(Class, Supplier, Predicate)`:

- `responseEntity(...)` rather than `entity(...)`, because `entity()` discards the completion it parsed. A
  rejected answer then has no raw text to report, and fetching it separately would sample a **second**
  completion — every accessor on `CallResponseSpec` re-issues the request, nothing is cached.
  `responseEntity()` returns `ResponseEntity<ChatResponse, T>`: one request, both the record and the text.
- When a target type is supplied, Spring AI appends the JSON schema to the prompt itself, so the controller
  only restates the **flat top-level key list** after it. A small model shown a JSON Schema will copy the
  schema and fill values under `"properties"`; the answer shape is stated last, where the model reads it last.
  (Braced template variables are StringTemplate delimiters, so a schema must never be inlined as template
  text.)
- `JSON_OPTIONS` requests `ResponseFormat.Type.JSON_OBJECT` at temperature 0.2 — low but not zero, because
  greedy decoding can lock onto an empty field and repeat it, which a retry with the same prompt would
  otherwise reproduce.
- Two attempts, then `StructuredOutputParseException` → **422** carrying `rawModelOutput` and
  `conversionFailure`.

### [`MultimodalController.java`](src/main/java/com/example/springai/controller/MultimodalController.java)

`GET /vision/analyze?imageUrl=…&question=…`: `publicHttpUri` parses the URL, requires absolute http(s), and
resolves it — refusing **any** loopback / link-local / site-local / any-local / IPv6-ULA address, because the
server fetches the caller's URL and an unchecked value reaches internal hosts and cloud metadata endpoints.
`RestClient` downloads with a browser `User-Agent` (Wikimedia answers 403 to headerless requests, and rejects
resized-thumbnail paths with 400) into a `byte[]`, so the media type comes from the response headers rather
than a guessed extension; it is attached as `new Media(mimeType, new ByteArrayResource(bytes))`. Download
failures raise `ImageDownloadException` → 400; model failures propagate so the advice can report the real
status and cause.

---

## 5. Service layer

### [`RagDocumentIngestionService.java`](src/main/java/com/example/springai/service/RagDocumentIngestionService.java)

- `run(String...)` — `CommandLineRunner` that ingests every `classpath:/docs/*.md`. Wrapped in try/catch so a
  missing or dead embedding backend **cannot block startup**; it logs
  `Document ingestion deferred or offline`. This is why `/actuator/health` reports UP without a model.
- `ingest(Resource, filename, origin)` — `TextReader` → metadata (`filename`, `origin`, `ingestedAt`) →
  `TokenTextSplitter` (chunk 120 tokens, min 50 chars, keep separator, max 200 chunks) → `vectorStore.add`.
  Chunk size is the whole game for a 494M model: at the original 400-token setting the entire policy file was
  **one** vector, so every question matched the same chunk and answers came from whichever section the model
  read first. `origin` distinguishes `classpath` from `upload` when filtering.
- `evict(filename)` — `vectorStore.delete(FilterExpression.eq("filename", …))`, used before a re-upload.

### [`OrderToolService.java`](src/main/java/com/example/springai/service/OrderToolService.java) / [`WeatherToolService.java`](src/main/java/com/example/springai/service/WeatherToolService.java)

`@Bean` + `@Description` `Function<Request, Details>` over in-memory fixtures. Each logs
`[SPRING-AI-TOOL] Executing … for …=<arg>`; grepping that line is the only proof that a tool ran instead of
the model inventing an answer (the 0.5B model fabricates weather roughly half the time and leaves no such
line). Request records are trimmed and upper-cased in the tool, and `@Description` is the text the model sees
when deciding whether to call it.

---

## 6. Cross-cutting layer

### [`GlobalExceptionHandler.java`](src/main/java/com/example/springai/error/GlobalExceptionHandler.java)

`@RestControllerAdvice` for failures Spring MVC would otherwise hand to Tomcat — a container stack dump plus a
body that says nothing about the model backend.

| Exception | Status | `error` | Notes |
| :--- | :--- | :--- | :--- |
| `StructuredOutputParseException` | 422 | `model_output_unparseable` | adds `rawModelOutput`, `conversionFailure` |
| `ImageDownloadException` | 400 | `image_unavailable` | |
| `MethodArgumentNotValidException` | 400 | `invalid_request` | joined field violations; without it a blank question surfaces as an `IllegalArgumentException` from several frames below the controller |
| `ResponseStatusException` | its own | `request_rejected` | |
| `MaxUploadSizeExceededException` | 413 | `document_too_large` | fires before the controller sees the file |
| `ResourceAccessException` | 503 | `llm_backend_unreachable` | `Retry-After: 5` |
| `TransientAiException` | 503 | `llm_backend_unavailable` | `Retry-After: 5` |
| `NonTransientAiException` | 502 | `llm_backend_rejected` | |

Every body: `error`, `status`, `detail` (whitespace-collapsed, 300 chars), `requestId` from MDC. Deliberately
absent: a catch-all `Exception` handler and custom 404/405, which would replace Spring's standard statuses
with 500.

### [`LlmHealthIndicator.java`](src/main/java/com/example/springai/health/LlmHealthIndicator.java)

`@Component("llm")`. Sends a one-token prompt (`Reply with the single word: ok`) through the real
`ChatModel`, reporting UP with `model`, `reply`, `latencyMs`, `probedAt`, or DOWN with `error`, `cause`,
`latencyMs`. Results are cached for `app.health.llm.cache-ttl` (30s) because an uncached live probe turns
health polling into the dominant load on the model — with a concurrency ceiling of 4, a 1-second poller could
starve real traffic. `management.endpoint.health.show-details: always` is what makes the model name and
latency visible.

### [`TokenUsageAdvisor.java`](src/main/java/com/example/springai/advisor/TokenUsageAdvisor.java)

`before` is identity; `after` logs to the `TOKEN_USAGE` logger. See §2 for the ordering requirement and the
zero-usage stream case.

---

## 7. Persistence

Chat memory: `SPRING_AI_CHAT_MEMORY` in the H2 file at `./data/chat-memory` (gitignored;
`CHAT_MEMORY_JDBC_URL` overrides, e.g. `jdbc:h2:mem:` for a throwaway store). `initialize-schema: always` plus
Boot's continue-on-error behaviour means the shipped script — which has no `IF NOT EXISTS` — is re-run and its
duplicate-table error ignored, so a second boot keeps its rows instead of failing. Verified across three
restarts.

`SimpleVectorStore` is in RAM by design and re-ingests from the classpath at boot.

---

## 8. What changed moving from Spring AI 1.0.0-M4 to 1.1.8

| Change | Action taken |
| :--- | :--- |
| Starter artifact renamed `spring-ai-openai-spring-boot-starter` → `spring-ai-starter-model-openai` | `pom.xml` |
| Modules split: `SimpleVectorStore` in `spring-ai-vector-store`, `QuestionAnswerAdvisor` in `spring-ai-advisors-vector-store`, both previously in `spring-ai-core` | two added dependencies |
| `Document.getScore()` exists | `/rag/search` returns real cosine scores instead of recomputing embeddings by hand |
| `MessageChatMemoryAdvisor` rejects a call without `ChatMemory.CONVERSATION_ID` | a separate stateless `chatClient` bean for the memoryless endpoints, and `requireConversationId` on every memory route |
| `CallResponseSpec.responseEntity(...)` returns the `ChatResponse` alongside the entity | replaces the M4-era manual `BeanOutputConverter` path; `entity()` would still re-issue the request |
| Retry auto-configuration repackaged (`org.springframework.ai.autoconfigure.retry` → `org.springframework.ai.retry.autoconfigure`) | `logging.level` key updated, or the retry interceptor's per-attempt stack traces come back |
| AI metrics now report under the `gen_ai.*` conventions | `/actuator/metrics` lists `gen_ai.client.operation`, `gen_ai.client.operation.active` and `gen_ai.client.token.usage`; the `spring.ai.advisor` / `spring.ai.chat.client` names present on the milestone build are gone |
| Boot 3.3.4 → 3.5.16 | `spring.http.client.*` timeouts bind into the `RestClient.Builder` the OpenAI starter consumes |
| springdoc 2.8.17 | 2.8.x is the Boot 3 line; springdoc 3.x targets Boot 4 |

---

## 9. How to test

```bash
mvn -B test
```

19 tests, no Spring context and no model calls: `ControllerIntegrationTest` builds MockMvc standalone and
fakes `ChatClient` with `java.lang.reflect.Proxy` (`entity()`/`responseEntity()` return the payload,
`content()` returns `""` unless the payload is a `String`, every other chain method returns self). Keep
`.responseEntity(...)`/`.entity(...)` in the controllers — rewriting them to `.content()` plus a manual
converter breaks `testStructuredOutputControllerStandalone`. `AiConcurrencyLimitFilterTest` drives the filter
with a holder thread that keeps the only permit, asserting the second call gets 503 + `Retry-After` and never
reaches the chain. Live behaviour (grounded RAG answers, token log lines, tool execution, SSE incremental
delivery, health DOWN with no backend) has to be exercised against a real model — see `README.md`.
