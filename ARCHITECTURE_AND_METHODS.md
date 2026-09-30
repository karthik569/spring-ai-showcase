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
| `vectorStore(EmbeddingModel)` | `SimpleVectorStore` — in-memory, cosine, `all-minilm` vectors. | Zero-infrastructure RAG. Returns the **subtype**, not `VectorStore`, because persistence lives on it (`save`/`load`); every injection point still asks for the interface. Snapshotted to `./data/rag` by `RagStorePersistence`, so uploads survive a restart. The alternative is still a real vector database, which this showcase deliberately does not pull in. |

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
| `queryKnowledgeBase` | `POST /api/ai/rag/query` | `QuestionAnswerAdvisor` retrieves, the model answers, and citations come from `response.context().get(RETRIEVED_DOCUMENTS)` — literally the chunks that reached the prompt. `filename` maps to the advisor's `FILTER_EXPRESSION` parameter. `RagQueryRequest` is `{question, filename}` only: how many chunks get injected is `app.rag.top-k`, a deployment setting, and the per-request `topK` field that used to sit here was validated, documented and never read. |
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

- `run(String...)` — `CommandLineRunner` that restores the snapshot, then refreshes every
  `classpath:/docs/*.md`. Wrapped in try/catch so a missing or dead embedding backend **cannot block
  startup**; it logs `Document ingestion deferred or offline`. This is why `/actuator/health` reports UP
  without a model.
  ```java
  boolean restored = persistence.restore();
  persistence.beginBatch();                       // one save after the loop, not one per document
  for (Resource resource : resolver.getResources("classpath:/docs/*.md")) {
      if (restored && checksum != null && checksum.equals(persistence.checksumOf(filename).orElse(null))) { … continue; }
      evict(filename);                            // unconditional; see the note below
      ingest(resource, filename, "classpath");
  }
  // finally: persistence.endBatch();
  ```
  The `evict` is unconditional rather than only on the restored path, because a snapshot the integrity probe
  rejected can still hold half a document's chunks — re-ingesting on top of those would double-index them, and
  deleting from an empty store costs nothing. The loop keys on **checksum, not filename**: unchanged bytes are
  logged as `Kept persisted chunks for company-policy.md (unchanged)` and never re-embedded, changed bytes
  replace only that document. Chunks with `origin=upload` are outside this loop entirely.
- `ingest(Resource, filename, origin)` — `TextReader` → metadata (`filename`, `origin`, `ingestedAt`) →
  `TokenTextSplitter` (chunk 120 tokens, min 50 chars, keep separator, max 200 chunks) → `vectorStore.add` →
  `persistence.remember(filename, sha256)`. The digest is taken **before** `TextReader` consumes the stream, so
  the recorded bytes are the bytes that were indexed; an empty split returns 0 without recording. Chunk size is
  the whole game for a 494M model: at the original 400-token setting the entire policy file was **one** vector,
  so every question matched the same chunk and answers came from whichever section the model read first.
  `origin` distinguishes `classpath` from `upload` when filtering.
- `evict(filename)` — `vectorStore.delete(FilterExpression.eq("filename", …))` plus `persistence.forget`, used
  before a re-upload and by the boot refresh.

### [`RagStorePersistence.java`](src/main/java/com/example/springai/service/RagStorePersistence.java)

Two files, one owner each: `data/rag/vector-store.json` is `SimpleVectorStore.save()`'s own serialization and is
opaque to this class; `data/rag/rag-store.json` is ours — `{"version":1,"checksums":{filename:sha256}}`. A
restore needs **both**; either missing or unreadable is a cold start (the pre-persistence behaviour) plus a
log line, never an exception. Every method is `synchronized`, and neither `restore()` nor `persist()` throws.

| Method | Behaviour |
| :--- | :--- |
| `restore()` | Reads the manifest, `vectorStore.load(file)`, runs the integrity probe, returns true only when the store genuinely holds what the manifest claims. |
| `persist()` | `Files.createDirectories` first (`save(File)` uses `Files.createFile` and needs the parent), then both files. No-op while not `dirty`. |
| `checksumOf` / `remember` / `forget` | The manifest. `remember`/`forget` mark it dirty, so `save-on-write` controls whether each upload hits the disk immediately. |
| `beginBatch` / `endBatch` | Depth counter suppressing per-write saves during boot; `endBatch` persists at depth 0. |
| `persistOnShutdown()` | `@PreDestroy`, so a dirty buffer is not lost when the process is stopped cleanly. |
| `sha256(Resource)` | Static, streams through `MessageDigest`, works from the exploded classpath **and** from inside a jar. |

**Why a checksum manifest at all:** `VectorStore` exposes no scan or count. "Is this document already indexed?"
could otherwise only be answered by an embedding-backed probe query — i.e. it would need Ollama running at boot,
which is exactly the coupling the try/catch above exists to avoid.

**The integrity probe.** A truncated `vector-store.json` can still parse into an *empty* store. Without a check,
the boot log would claim the chunks were kept while the knowledge base holds nothing. So when the manifest is
non-empty, one `similaritySearch(query "policy", topK 1, similarityThresholdAll())` decides: 0 hits ⇒
`Discarding stale or partial vector snapshot …` (WARN) and a full re-index. The probe needs the embedder, so if
it raises instead of returning, the snapshot is **kept** with a debug line — re-indexing would fail for the same
reason and only leave the store emptier.

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

Vector store: two JSON files under `./data/rag` (gitignored; `RAG_STORE_DIRECTORY` overrides, and
`RAG_STORE_ENABLED=false` reverts to the pre-persistence behaviour of an ephemeral in-RAM store re-ingested from
the classpath at every boot). `SimpleVectorStore` is still in RAM at serve time — the snapshot exists so that
documents uploaded through `POST /rag/documents` survive a restart, which re-embedding the classpath never did.
Same chunk id before and after, so `evict(filename)` semantics are unchanged by persistence. See §5 for the
manifest, the checksum-keyed refresh and the integrity probe; `README.md` §6 for the three env knobs.

A filename collision is the one destructive edge, and persistence makes it permanent rather than self-healing:
upload a file named `company-policy.md` and the shipped document's chunks are evicted (3 → 1). On the next boot
the snapshot restores the uploaded chunk, the refresh loop finds the shipped bytes differ from the manifest entry
it no longer has, and re-indexes 3 chunks under that filename — the uploaded text is gone. Verified: 3 chunks →
upload → 1 chunk → restart → 3 chunks.

`SimpleVectorStore.save()` logs `Overwriting existing vector store file` on every write, so a chatty
`RAG_STORE_SAVE_ON_WRITE=false` plus the `@PreDestroy` flush is the quiet alternative.

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
mvn -B test                              # 32 tests: 31 run offline, 1 skipped
mvn -B test -Dapp.rag.eval=true          # + the live tier-2 retrieval table
```

Three tiers, separated by what they need to be up — not by a tag, because a tag would need surefire
configuration and the build file is deliberately untouched.

**Tier 0 — endpoints, no model.** `ControllerIntegrationTest` builds MockMvc standalone and fakes `ChatClient`
with `java.lang.reflect.Proxy`
(`entity()`/`responseEntity()` return the payload, `content()` returns `""` unless the payload is a `String`,
every other chain method returns self). Keep `.responseEntity(...)`/`.entity(...)` in the controllers —
rewriting them to `.content()` plus a manual converter breaks `testStructuredOutputControllerStandalone`. Its
vector store is no longer a null-returning proxy: `RagStorePersistence`'s constructor needs the `SimpleVectorStore`
subtype, so those tests now inject a real store over `StubEmbeddingModel` with persistence pointed at a directory
nothing writes to. Strictly better coverage for the same speed. `AiConcurrencyLimitFilterTest` drives the filter
with a holder thread that keeps the only permit, asserting the second call gets 503 + `Retry-After` and never
reaches the chain.

**Tier 1 — retrieval quality, offline and deterministic.** `GoldenQuestionsTest` ingests
`src/main/resources/docs/company-policy.md` plus a test-only `src/test/resources/rag/corpus/ops-runbook.md` into
a **real** `SimpleVectorStore` over `StubEmbeddingModel`, then measures 15 rows from
`src/test/resources/rag/golden-questions.json` against `src/test/java/…/rag/RetrievalMetrics.java`. Stubbing only
the embedder is the whole trick: `TokenTextSplitter`, the cosine math, the filter evaluator and the ranking all
run for real, so a regression in any of them is caught with Ollama switched off. Two properties make the gate
drift-proof: the harness always searches with `similarityThresholdAll()` (so
`app.rag.similarity-threshold` cannot move a metric), and pass/fail is on **rank**, never score value —
the observed `0.1176 → 0.1228` wobble does not reorder distinct sections, so a rank flip is attributable. The
second test in the class asserts the floor itself: a threshold just above the best score turns a non-empty result
set into an empty one, which is the "RAG is silently not grounded" lesson from `docs/GETTING_STARTED.md` proven
without a model.

`RagStorePersistenceTest` (9 tests, `@TempDir`, no context) covers the restore/persist contract: an upload
surviving a restart **with the same chunk id**, a corrupt snapshot starting cold instead of bricking boot, a
`{}` vector file against a non-empty manifest being discarded by the probe, an unchanged shipped document not
indexed twice, changed bytes replacing only that document, an eviction persisting, a missing directory being
created before save, and both `enabled=false` / `save-on-write=false`.

**Tier 2 — the same dataset against `all-minilm`.** `LiveRetrievalComparisonTest` is
`@EnabledIfSystemProperty(named = "app.rag.eval", matches = "true")`, so without the property it reports
*skipped*, which is green; opting in costs no build-file change. It must override
`spring.datasource.url` to an in-memory H2 (the file-backed chat memory is exclusively locked while the app runs,
and tier 2 is exactly the moment both are up) and set `app.rag.store.enabled=false`, because reading
`./data/rag` would measure whatever the last manual upload left behind. Ranks are **printed, not asserted** — the
vector leg genuinely struggles with acronym queries and the scores move run to run. `OllamaReachable.orSkip`
probes first so a stopped backend reads as a skip rather than an error.

**What still needs a real model:** grounded RAG *answers*, token log lines, tool execution, SSE incremental
delivery, health DOWN with no backend. See `README.md`.
