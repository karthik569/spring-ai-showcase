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

### The same chain's second trap: memory is injected where retrieval cannot see it

Also read from bytecode, and the reason `RagQueryResolver` exists as a service rather than as an advisor.

- `QuestionAnswerAdvisor.before()` builds its `SearchRequest` from `prompt.getUserMessage().getText()` — the
  **last** `UserMessage` — and then *replaces* that text with the rendered context template. The retrieval query
  is therefore one string, taken before the model sees anything.
- `MessageChatMemoryAdvisor.before()` injects history as **separate messages** and never touches the user text.
  Its order is `Integer.MIN_VALUE + 1000`, `QuestionAnswerAdvisor`'s is `0`, so memory really is in the prompt
  when retrieval runs — and is still invisible to it.
- So attaching the memory advisor to the RAG client buys nothing for a follow-up: *"and what about travel?"*
  is searched as written. The query string has to become standalone **before** `chatClient.prompt()`, which is
  what [`RagQueryResolver`](src/main/java/com/example/springai/service/RagQueryResolver.java) does, reading
  `ChatMemory` directly instead of hoping an advisor forwards it.
- A third mechanic decides where the rewriter lives: `ChatClient.Builder` is prototype-scoped but
  `defaultAdvisors(...)` **accumulates** (bullet 2 above). Building the rewrite client from the same builder
  inside `RagController` would inherit `QuestionAnswerAdvisor` and answer the *rewrite prompt* with retrieved
  policy text, so it is its own bean — `ragQueryRewriter` — with no default advisors at all.

---

## 3. Configuration layer

### [`AiConfig.java`](src/main/java/com/example/springai/config/AiConfig.java)

| Method | Behaviour | Why |
| :--- | :--- | :--- |
| `chatMemory(ChatMemoryRepository)` | `MessageWindowChatMemory` bounded to **6** messages over the auto-configured JDBC repository. | A window, not the whole transcript: the local model's context is small enough that unbounded history pushes the actual question out of the prompt. The repository owns its schema; this bean only bounds the window. |
| `tokenUsageAdvisorCustomizer()` | `ChatClientCustomizer` adding `TokenUsageAdvisor` to every builder. | Controllers build clients in their constructors (RAG needs a store-specific advisor); a customizer means each one does not re-declare token accounting. |
| `chatClient(ChatClient.Builder)` | Stateless client with the shared system prompt. | Tools, structured output and vision have no conversation. Since Spring AI **1.1.6** `MessageChatMemoryAdvisor` *rejects* a call with no conversation id, so a memory-carrying client could not serve them even if it were desirable. |
| `conversationalChatClient(ChatClient.Builder, ChatMemory)` | `@Qualifier("conversationalChatClient")`; adds `MessageChatMemoryAdvisor`. | Serves `/chat/memory`, `/chat/stream`. Every call must supply `ChatMemory.CONVERSATION_ID` — that parameter is what keeps two callers' histories apart. |
| `ragQueryRewriter(ChatClient.Builder)` | `@Qualifier("ragQueryRewriter")`; system prompt + `temperature 0.0`, `maxTokens 64`, and **no** default advisors. | The follow-up resolver's client. Separate because `defaultAdvisors` accumulates: built from the RAG builder it would inherit `QuestionAnswerAdvisor` and retrieve policy text in answer to its own rewriting instruction. Deterministic options because a rewriter that improvises has to be validated, not trusted. |
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

`ConversationIds.requireNonBlank` rejects blanks and lengths above **36 characters** at the boundary. The JDBC
schema stores the id in `VARCHAR(36)`, so a longer value fails on insert deep inside `JdbcTemplate` and arrives as an
opaque 500.

The rule lives in [`support/ConversationIds.java`](src/main/java/com/example/springai/support/ConversationIds.java)
rather than in this controller because `POST /rag/query` takes the same id — with a deliberate asymmetry:
`ChatController` requires one (a memory endpoint without an id is meaningless), while `/rag/query` treats a blank
as "no conversation" and stays the single-shot endpoint it was. Same 36-character ceiling, two policies.

### [`RagController.java`](src/main/java/com/example/springai/controller/RagController.java)

| Method | Route | Notes |
| :--- | :--- | :--- |
| `queryKnowledgeBase` | `POST /api/ai/rag/query` | `QuestionAnswerAdvisor` retrieves, the model answers, and citations come from `response.context().get(RETRIEVED_DOCUMENTS)` — literally the chunks that reached the prompt. `filename` maps to the advisor's `FILTER_EXPRESSION` parameter. `RagQueryRequest` is `{question, filename, conversationId}`: how many chunks get injected is `app.rag.top-k`, a deployment setting, and the per-request `topK` field that used to sit here was validated, documented and never read. The retrieval text is `queryResolver.resolve(...)` first and `.user(...)` second — see §2 for why an advisor cannot do this — and the turn is written back with `queryResolver.remember(...)` only after a non-blank answer, never from a failure path. |
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

### [`RagQueryResolver.java`](src/main/java/com/example/springai/service/RagQueryResolver.java)

Turns a follow-up into text the embedder can answer, and writes the RAG turn back into chat memory. The
*whether* was measured first (§Phase 0 of
[`docs/SESSION-HANDOFF.md`](docs/SESSION-HANDOFF.md)): two of the three follow-up rows already
retrieve their gold chunk at rank 1 unresolved, so this is not a ranking fix — it is the floor and the citation
list. Every decision below is a rule the running application forced, not one the design assumed.

| Method | Behaviour | Why |
| :--- | :--- | :--- |
| `resolve(question, conversationId)` | Returns `{retrievalQuery, outcome, rewriteTimeMs}`; six outcomes, one of which is always reported. | `PASSTHROUGH` would otherwise look like `REWRITTEN` to whoever reads the log, and "the model decided" is not a state an endpoint should advertise without saying what it did. |
| `remember(conversationId, rawQuestion, answer)` | One `chatMemory.add(id, List.of(UserMessage, AssistantMessage))`, skipped when disabled, blank-id or blank-answer. | The **raw** question is stored so `/chat/memory/history` keeps meaning "what the user typed"; the answer is stored even when ungrounded because the next *"what's the budget for that?"* refers to the assistant's own prose. Never called from a failure path — a 503 must not leave a half turn behind. |

Rules on the model's output, in order, because a 494M model does all of these:

- null → `""`; keep only the first line (`\\R`); collapse whitespace; strip a leading
  `SAME|REWRIT\\w*|REVISED|STANDALONE|QUESTION [?: question]` **only when punctuation follows** — `Same-day
  courier costs…` is a real rewrite and a hyphen in the separator class used to eat it.
- blank, `SAME`, or equal to the normalized input → `PASSTHROUGH`. The model's own self-report is never
  authoritative: it replies `SAME` to questions that need a rewrite and re-expands questions that do not.
- a rewrite that still contains a demonstrative is discarded **unless it borrowed a content word from the
  conversation**. The unqualified version of this rule was wrong, and only the running application showed it:
  for *"how long do I have to submit that?"* the model produced *"How long does **it** take to submit the home
  office equipment stipend?"* — a correct substitution carrying a placeholder `it` — which was thrown away, and
  the request then cited a chunk at 0.219 from the wrong section. Over-rejection is not a neutral fallback here;
  it reinstates the bug the service exists to remove. The converse half, `namesSomethingFromTheConversation`,
  also rejects an invented noun, which is the model answering instead of resolving.
- over `app.rag.follow-up.max-query-chars` → cut at the last whitespace before the cap, and `PASSTHROUGH` if
  that cut lost the terminal `?`/`.` — searching a half sentence is worse than searching the pronoun.

**Failure policy: backend exceptions are not caught here.** `ResourceAccessException` and the
`TransientAiException`/`NonTransientAiException` pair reach `GlobalExceptionHandler` as `503`/`502`. Falling back
on *useless output* is allowed (nothing is invented, it degrades visibly to single-shot, and `resolvedQuestion`
plus `followUpOutcome` say so); falling back on an unreachable model would be the canned-answer pattern this
repo refuses everywhere else. Verified live: kill Ollama between two turns and the follow-up is
`503 llm_backend_unreachable`, and the conversation still holds exactly the four messages from the successful
turns.

Window arithmetic: at most `max-history-turns` (2) pairs, each line truncated to 200 chars, because the memory
window is 6 messages and feeding all of it re-creates the context overflow `AiConfig` warns about.

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
| `MessageChatMemoryAdvisor` rejects a call without `ChatMemory.CONVERSATION_ID` | a separate stateless `chatClient` bean for the memoryless endpoints, and a conversation-id guard on every memory route (since extracted into `ConversationIds.requireNonBlank`) |
| `CallResponseSpec.responseEntity(...)` returns the `ChatResponse` alongside the entity | replaces the M4-era manual `BeanOutputConverter` path; `entity()` would still re-issue the request |
| Retry auto-configuration repackaged (`org.springframework.ai.autoconfigure.retry` → `org.springframework.ai.retry.autoconfigure`) | `logging.level` key updated, or the retry interceptor's per-attempt stack traces come back |
| AI metrics now report under the `gen_ai.*` conventions | `/actuator/metrics` lists `gen_ai.client.operation`, `gen_ai.client.operation.active` and `gen_ai.client.token.usage`; the `spring.ai.advisor` / `spring.ai.chat.client` names present on the milestone build are gone |
| Boot 3.3.4 → 3.5.16 | `spring.http.client.*` timeouts bind into the `RestClient.Builder` the OpenAI starter consumes |
| springdoc 2.8.17 | 2.8.x is the Boot 3 line; springdoc 3.x targets Boot 4 |

---

## 9. How to test

```bash
mvn -B test                              # 101 tests: 98 run offline, 3 skipped
mvn -B test -Dapp.rag.eval=true          # + the two live tier-2 tables (golden questions, follow-ups)
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

One trap to know before adding an advisor-shaped feature here: the fake `ChatClient` intercepts `prompt`, so an
advisor's `before()` **never runs** under tier 0 — code that only lives in an advisor is invisible to these tests
and looks covered when it is not. That is also why follow-up resolution is a service the controller calls
explicitly. Tier 0 gives `RagQueryResolver` its *own* fake client, distinct from the one serving the RAG answer,
which means the rewrite path can be asserted (`$.followUpOutcome`, `$.resolvedQuestion`, the four messages written
back) without adding a single method to the existing proxies.

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

`FollowUpRetrievalTest` (3 tests) and `RagQueryResolverTest` (22, plain JUnit, no context) are tier 1's other
half, and they break the "always `similarityThresholdAll()`" rule above **on purpose**. A follow-up's failure is
its gold chunk scoring under the floor and being dropped, so that dataset
(`src/test/resources/rag/follow-up-questions.json`, its own file because the 15 shipped rows carry rank budgets a
follow-up row cannot express — `"expectRaw": "miss"` means something different there) is measured at the shipped
0.2: unresolved retrieves nothing on the stub, resolved retrieves the answer at 0.248. The resolver tests are
where the model's worst habits are pinned — `SAME`, a labelled output, a multi-line ramble, a 500-character
query, the fragment `to SUBMIT that?`, and a `ResourceAccessException` that must propagate rather than degrade —
with `RecordingChatClient` asserting that memory actually reached the rewrite prompt.

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
probes first so a stopped backend reads as a skip rather than an error. The class now has two methods: the 15
golden questions, and the follow-up table at the served floor, report-only for the same reason — on the live
embedder one row went from *the floor dropping its gold chunk entirely* to rank 1 at 0.604 once resolved, while
the other two already ranked 1 unresolved, which is a result you do not want a green build hiding.

**What still needs a real model:** grounded RAG *answers*, token log lines, tool execution, SSE incremental
delivery, health DOWN with no backend. See `README.md`.

---

## 10. Cache, hybrid retrieval, the agent loop and metrics

### [`SemanticCacheAdvisor.java`](src/main/java/com/example/springai/cache/SemanticCacheAdvisor.java)

A `CallAdvisor` at `HIGHEST_PRECEDENCE + 10` — the outermost, ahead of the terminal advisor, because a hit has
to *short-circuit*: returning a response without calling `chain.nextCall` is what skips the memory advisor, the
model and `TokenUsageAdvisor` together. A miss wraps the whole chain, so the answer it stores is the one the
caller actually got.

The store is its own `SimpleVectorStore`, deliberately not the knowledge base: a cached answer is keyed by a
question and must never surface as a retrieved document in a RAG citation. A lookup is a vector search over
prior questions, so a paraphrase hits, not just a repeated string.

Two things are load-bearing. First, several requests are never cached — a tool call (side effects, fresh data),
a conversation turn (history the key ignores), an image (not in the text), a structured output (a schema the
cached plain text cannot satisfy) — each excluded in `cacheableKeyOf` rather than cached wrongly. Second, the
stored `Document` gets a `ContentFormatter` that returns only its text. `SimpleVectorStore.doAdd` embeds a
document through `OpenAiEmbeddingModel.embed(Document)` → `getFormattedContent(MetadataMode.EMBED)`, which folds
the metadata in, while `doSimilaritySearch` embeds the bare query string. Left alone, a question is stored as
"question + answer + timestamp" and can never match itself at the 0.95 floor; formatting the stored key to its
text alone puts both sides on the same footing. `SemanticCacheAdvisorTest` models that asymmetry with a
`MetadataInclusiveEmbeddingModel`, which is why the test fails if the formatter is removed.

Config: `app.cache.enabled`, `similarity-threshold` (0.95), `max-entries` (200, FIFO eviction), `ttl` (PT30M).

### [`HybridVectorStore.java`](src/main/java/com/example/springai/vectorstore/HybridVectorStore.java) / [`KeywordIndex.java`](src/main/java/com/example/springai/vectorstore/KeywordIndex.java)

A `@Primary` decorator, not a replacement: it implements `VectorStore`, so every caller — the
`QuestionAnswerAdvisor`, `/rag/search`, `KnowledgeBaseToolService` — gets hybrid retrieval without knowing it
is talking to a decorator. The vector leg remains the source of truth for a chunk's reported `score` (its
cosine); the decorator only changes *order* and *membership*.

The two ranked lists are merged by reciprocal rank fusion (`rrf-k`), which needs no calibration between a
cosine and a BM25 number. A document is eligible if the vector leg ranked it at or above the threshold **or**
the keyword leg matched a query term — the union is what recovers the acronym chunk the cosine floor drops. The
corpus is small, so the vector leg runs over a pool at least as wide as the index, meaning a keyword-only
document still usually carries a real cosine to report. `rebuildKeywordIndex()` repopulates the BM25 leg after
`RagStorePersistence` restores chunks straight into the inner store, bypassing this decorator's `add`.

### [`AgenticLoopService.java`](src/main/java/com/example/springai/agent/AgenticLoopService.java)

`GET /api/ai/tools/agent` runs the loop itself instead of delegating to Spring AI's internal tool execution:
`ChatModel.call`, and while the response has tool calls, `ToolCallingManager.executeToolCalls(prompt, response)`
and repeat, with `internalToolExecutionEnabled(false)` on the options so the framework does not also run them.
Bounding the rounds in `app.agent.max-steps` is what stops a model that will not stop asking for tools; on
exhaustion the service nudges once with the tools withdrawn (`answerWithoutTools`) so the caller gets prose
rather than an error. The run returns an ordered `Step` list (each `tool_call` and `tool_result`), `modelCalls`,
`toolCalls` and `budgetExhausted`, and the final answer is PII-redacted like any other. The toolbox is
`app.agent.tools`, so withdrawing a tool from the loop is a config change, not a code change.

### [`AiMetrics.java`](src/main/java/com/example/springai/observability/AiMetrics.java) / [`AiRequestMetricsFilter.java`](src/main/java/com/example/springai/config/AiRequestMetricsFilter.java) / [`MetricsController.java`](src/main/java/com/example/springai/controller/MetricsController.java)

`AiRequestMetricsFilter` (order `HIGHEST_PRECEDENCE + 300`, *inside* `AiConcurrencyLimitFilter`, so a 503 the
filter refuses is not counted as a served call) times every `/api/ai` request and folds it into `AiMetrics`.
The Micrometer instruments (`ai.requests.total`, `ai.tokens.total`, `ai.latency`, `ai.cache.requests.total`) are
the durable record; the in-process counters exist because a Micrometer counter cannot be enumerated back into a
per-endpoint table, and that table is exactly what `MetricsController` renders at `GET /api/ai/metrics/summary`.
Percentiles come from a bounded ring of the last 512 latencies — a real histogram would be more machinery than
the showcase needs. A streamed call is measured down to the point the request goes async (time to first byte),
not the end of the generation.

