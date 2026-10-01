# Spring AI Showcase 🤖

A Spring AI reference application built with **Java 21**, **Spring Boot 3.5.16** and **Spring AI 1.1.8** (GA).

It demonstrates **ChatClient**, **multi-turn chat memory persisted to a database and compacted by a rolling
summary**, **Retrieval-Augmented Generation with document upload, metadata filters, real similarity scores,
an optional LLM re-rank stage and a coverage gate that refuses an unanswerable question instead of answering
it from memory**, **Dynamic Tool / Function Calling**, **Structured Record Output Mapping**, **Multimodal
Vision**, **reactive token streaming**, and the operational layer a model-backed endpoint needs: **timeouts,
a live LLM health check, a concurrency ceiling, correlated request logging, and OpenAPI docs**.

Any OpenAI-protocol backend works — local Ollama, LM Studio, llama.cpp's server, or a hosted API key. Nothing
in the code is model-specific. The sample outputs below were produced by `qwen2.5:0.5b-instruct` via Ollama,
so read §Known limits before judging the answers; the shipped Termux launcher instead runs a larger
**Qwen2.5-1.5B** with a dedicated `all-minilm` embedder, so its answers are stronger than the 0.5B samples.

> **New to Spring AI?** Start with **[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)** — prerequisites, a
> plain-language glossary, and a five-step guided tour that runs every capability in learning order. This file
> is the reference; that file is the on-ramp.

---

## 🚀 Quick start (local Ollama — the mode every example here was run in)

```bash
ollama serve                        # or the ollama.exe on your PATH
ollama pull qwen2.5:0.5b-instruct   # chat model
ollama pull all-minilm              # embedding model — RAG needs this as well as a chat model

export OPENAI_API_KEY="ollama"                 # Ollama ignores it; Spring AI requires a non-empty value
export OPENAI_BASE_URL="http://localhost:11434" # no /v1 suffix; the client appends it
export OPENAI_MODEL="qwen2.5:0.5b-instruct"
export OPENAI_EMBEDDING_MODEL="all-minilm"

mvn -B spring-boot:run
```

Then open:

| URL | What it is |
| :--- | :--- |
| `http://localhost:8080/` | Demo UI — eleven panels, one per capability, with a live `requestId` on every result |
| `http://localhost:8080/docs` | Swagger UI (302 → `/swagger-ui/index.html`), served from the OpenAPI document at `/v3/api-docs` |
| `http://localhost:8080/actuator/health` | Includes the `llm` component, which sends a one-token prompt and reports model, latency and reply |

The app boots and serves `/actuator/health` even with no model running: RAG ingestion is wrapped in a
try/catch, so a dead backend logs `Document ingestion deferred or offline` instead of blocking startup. In that
state the `llm` health component is DOWN and AI calls answer `503` with `Retry-After`.

### Pointing at a hosted backend

The app speaks the OpenAI protocol, so this is config only — no code change:

```bash
export OPENAI_API_KEY="sk-..."
export OPENAI_BASE_URL="https://api.openai.com"
export OPENAI_MODEL="gpt-4o-mini"
export OPENAI_EMBEDDING_MODEL="text-embedding-3-small"
mvn -B spring-boot:run
```

Keep keys in the environment. `application.yml` is tracked and reads `${OPENAI_API_KEY}`; the logging filter
records request and response **bodies** but never **headers**, so a key cannot reach the log files.

> `OPENAI_BASE_URL` must serve both `/v1/chat/completions` and `/v1/embeddings`, and the embedder must be a real
> embedding model. Using a chat model as the vectorizer produces near-random cosine scores, so short queries
> match nothing.

### Termux / llama.cpp

`start-offline-llm.sh` and `start-spring-ai.sh` are Termux-only (`cd /sdcard/...`, `llama-server`) and do not
work on Windows or macOS. `start-offline-llm.sh` starts **two** llama.cpp servers, because `llama-server` loads
one model per process:

| Port | Model | Env |
| :--- | :--- | :--- |
| 8081 | `Qwen2.5-1.5B-Instruct` (Q4_K_M GGUF) | `OPENAI_BASE_URL` |
| 8082 | `all-MiniLM-L6-v2` (quantized, **embeddings**) | `OPENAI_EMBEDDING_BASE_URL` |

The embedder is a *real* embedding model on its own port, wired through `app.embedding.base-url` — this is the
caveat above handled the right way, so RAG scores from this mode are meaningful. (The chat server also accepts
`--embedding`, but that reuses the chat weights and is exactly what you must not point the vectorizer at.)

---

## 🏛 System Architecture

```
 Browser ── GET / ──────────► static/index.html   eleven panels, EventSource for /chat/stream
 cURL   ── GET /docs ───────► Swagger UI          OpenAPI 3.1 document at /v3/api-docs
 cURL   ── GET|POST /api/ai/** ──► controllers

   request
      │
      ├─ EndpointLoggingFilter      requestId → MDC, one line per call in logs/endpoints.log
      ├─ AiConcurrencyLimitFilter   ≤4 model calls in flight, else 503 + Retry-After
      ├─ AiRequestMetricsFilter     times every /api/ai call ─► AiMetrics ─► /api/ai/metrics/summary
      │
      ├─ ChatController            ┐
      ├─ RagController             │
      ├─ ToolCallingController     ├─► ChatClient ─► advisor chain (stable-sorted)
      ├─ StructuredOutputController│        │   SemanticCacheAdvisor     HIGHEST_PRECEDENCE + 10 (chat only, short-circuits)
      ├─ MultimodalController      │        │   TokenUsageAdvisor          LOWEST_PRECEDENCE - 1
      └─ MetricsController         ┘        │   MessageChatMemoryAdvisor   window 30 + summary  ─► H2 ./data/chat-memory
                                            │   QuestionAnswerAdvisor     topK 4     ─► RerankingVectorStore ─► HybridVectorStore ─► SimpleVectorStore ─► ./data/rag/*.json
                                            ▼
      OpenAI-protocol backend: Ollama · OpenAI · LM Studio · llama.cpp
                                            │
                                            └─ any failure ─► GlobalExceptionHandler
                                                                → typed problem JSON + requestId
```

Spring AI appends its own terminal advisor (`ChatModelCallAdvisor`, order `Integer.MAX_VALUE`) to every
request, so an advisor that also claims `Ordered.LOWEST_PRECEDENCE` sorts *behind* the model call and never
runs. `TokenUsageAdvisor` uses `LOWEST_PRECEDENCE - 1` for that reason — see
[ARCHITECTURE_AND_METHODS.md](ARCHITECTURE_AND_METHODS.md).

---

## 🌟 Capabilities and where they live

| Feature | Component | Implementation notes |
| :--- | :--- | :--- |
| **Fluent `ChatClient`** | [`ChatController.java`](src/main/java/com/example/springai/controller/ChatController.java) | System/user roles, `{param}` templating, `Flux<String>` SSE streaming. |
| **Persisted multi-turn memory** | [`AiConfig.java`](src/main/java/com/example/springai/config/AiConfig.java) | `MessageWindowChatMemory` over the auto-configured JDBC repository and an H2 file database, so transcripts survive a restart. `/chat/memory/history` reads back exactly what the advisor will inject. |
| **Long-term memory (rolling summary)** | [`SummarizingChatMemory.java`](src/main/java/com/example/springai/memory/SummarizingChatMemory.java) | A `ChatMemory` decorator that folds everything older than the last few turns into one summary message once the stored history passes a trigger, so a long conversation keeps its opening instead of dropping it off a 6-message window. Every failure (a summarizer that throws or returns blank) leaves the transcript intact. Knobs: `app.memory.summarization.*`. |
| **Token accounting** | [`TokenUsageAdvisor.java`](src/main/java/com/example/springai/advisor/TokenUsageAdvisor.java) | Registered with a `ChatClientCustomizer` so *every* client logs prompt/completion/total to the `TOKEN_USAGE` logger; Spring AI also publishes `gen_ai.client.token.usage` to the metrics endpoint. |
| **RAG + upload + filters + scores** | [`RagController.java`](src/main/java/com/example/springai/controller/RagController.java) | `QuestionAnswerAdvisor` for grounded answers, `POST /rag/documents` for multipart indexing, a `filename` metadata filter, and `GET /rag/search` that surfaces each chunk's cosine score and explains zero matches. Every response reports `resolvedQuestion` (the text that was searched) and `groundingOutcome` (`GROUNDED` / `RE_QUERIED` / `REFUSED` / `DISABLED`). |
| **Follow-ups resolved before retrieval** | [`RagQueryResolver.java`](src/main/java/com/example/springai/service/RagQueryResolver.java) | A `conversationId` on `/rag/query` buys one cheap model call that rewrites "how long do I have to submit that?" into text an embedder can answer, reading persisted chat memory directly — the retrieval advisor builds its query from the user message and cannot see injected history. Validation decides, not the model's own confidence. |
| **Knowledge base that survives a restart** | [`RagStorePersistence.java`](src/main/java/com/example/springai/service/RagStorePersistence.java) | `SimpleVectorStore.save`/`load` over two files in `./data/rag`, plus a per-filename SHA-256 manifest so an unchanged shipped document is not re-embedded and a truncated snapshot is discarded loudly instead of silently empty. |
| **Offline retrieval eval** | [`GoldenQuestionsTest.java`](src/test/java/com/example/springai/rag/GoldenQuestionsTest.java), [`FollowUpRetrievalTest.java`](src/test/java/com/example/springai/rag/FollowUpRetrievalTest.java), [`LiveRetrievalComparisonTest.java`](src/test/java/com/example/springai/rag/LiveRetrievalComparisonTest.java) | 15 golden questions with verbatim answer markers plus 3 follow-up rows, rank-based metrics, and a stub embedder that makes the real cosine/filter/threshold code assertable with Ollama switched off. |
| **Tool / Function calling** | [`ToolCallingController.java`](src/main/java/com/example/springai/controller/ToolCallingController.java) | `Function<Request, Response>` beans described with `@Description` — weather, order, [`calculate`](src/main/java/com/example/springai/service/CalculatorToolService.java) (`+ - * / % ^`, hand-parsed, no script engine), [`getCurrentDateTime`](src/main/java/com/example/springai/service/DateTimeToolService.java), and [`searchKnowledgeBase`](src/main/java/com/example/springai/service/KnowledgeBaseToolService.java) (RAG as a tool) — bound per call with `.toolNames(...)`; the model decides whether to invoke them. `/tools/assistant` offers all five. |
| **Structured output** | [`StructuredOutputController.java`](src/main/java/com/example/springai/controller/StructuredOutputController.java) | `responseEntity(...)` parses into the record and keeps the raw completion; a flat answer shape is restated after the schema, an incomplete record retries once, then `422` with `rawModelOutput`. |
| **Multimodal vision** | [`MultimodalController.java`](src/main/java/com/example/springai/controller/MultimodalController.java) | Server-side download with a `User-Agent`, address-class screening, `Media` attachment on the prompt. |
| **Resilience & ops** | [`AiConcurrencyLimitFilter`](src/main/java/com/example/springai/config/AiConcurrencyLimitFilter.java), [`LlmHealthIndicator`](src/main/java/com/example/springai/health/LlmHealthIndicator.java), [`GlobalExceptionHandler`](src/main/java/com/example/springai/error/GlobalExceptionHandler.java) | HTTP timeouts, a cached live probe, a semaphore ceiling with `Retry-After`, and typed error bodies instead of container stack dumps. |
| **Semantic response cache** | [`SemanticCacheAdvisor.java`](src/main/java/com/example/springai/cache/SemanticCacheAdvisor.java) | The outermost advisor: a paraphrase of a question already answered is replayed from a vector index of prior questions without calling the model, short-circuiting the whole chain (memory, model, token logging). Requests whose answer depends on more than the prompt text — a tool, a conversation, an image, a structured schema — fall straight through. Tunable via `app.cache.*` / `SEMANTIC_CACHE_ENABLED`. |
| **Hybrid retrieval (BM25 + vector)** | [`HybridVectorStore.java`](src/main/java/com/example/springai/vectorstore/HybridVectorStore.java), [`KeywordIndex.java`](src/main/java/com/example/springai/vectorstore/KeywordIndex.java) | A `VectorStore` decorator that fuses the cosine ranking with a BM25 keyword ranking by reciprocal rank fusion, recovering a chunk that matches on a rare literal term (an acronym) but scores poorly as an embedding. Every caller (`QuestionAnswerAdvisor`, `/rag/search`, the knowledge-base tool) gets it for free; off via `RAG_HYBRID_ENABLED=false`. |
| **Re-rank stage (opt-in)** | [`RerankingVectorStore.java`](src/main/java/com/example/springai/vectorstore/RerankingVectorStore.java), [`LlmReranker.java`](src/main/java/com/example/springai/rag/LlmReranker.java) | A second decorator above the hybrid store that asks the model, in one listwise call, to reorder the fused candidates before trimming to `topK`. Off by default (`app.rag.rerank.enabled=false`) because on this small corpus the fused ranking already hits MRR 1.0, so a 1.5B re-ranker mostly adds latency and noise; it only ever reorders — a candidate is never dropped, and any failure keeps the fused order. |
| **Self-correcting RAG (coverage gate)** | [`RagCoverageGate.java`](src/main/java/com/example/springai/rag/RagCoverageGate.java), [`LlmContextGrader.java`](src/main/java/com/example/springai/rag/LlmContextGrader.java) | Before the answer model is called, grades the retrieved chunks for coverage. Nothing relevant broadens the search once (using the caller's own words); still nothing → the request is **refused** with the closest passage quoted, instead of answering from the model's own memory. A cosine floor cannot do this — an unanswerable question here scored 0.405, above three genuine matches. Knobs: `app.rag.crag.*`. |
| **Agentic multi-step loop** | [`AgenticLoopService.java`](src/main/java/com/example/springai/agent/AgenticLoopService.java) | `GET /api/ai/tools/agent` drives the tool-calling loop by hand — model call, `ToolCallingManager.executeToolCalls`, repeat — bounded by `app.agent.max-steps`, and returns the full ordered step trace. Hand-rolled rather than delegated to Spring AI's internal loop so each round is visible. |
| **Observability & cost** | [`AiMetrics.java`](src/main/java/com/example/springai/observability/AiMetrics.java), [`AiRequestMetricsFilter.java`](src/main/java/com/example/springai/config/AiRequestMetricsFilter.java), [`MetricsController.java`](src/main/java/com/example/springai/controller/MetricsController.java) | A servlet filter folds every `/api/ai` call into requests / tokens / cache hit-rate / latency percentiles, rendered at `GET /api/ai/metrics/summary` and also exported as Micrometer counters (`ai.requests.total`, `ai.tokens.total`, `ai.latency`, `ai.cache.requests.total`). |
| **Guardrails** | [`PiiRedactor.java`](src/main/java/com/example/springai/guardrail/PiiRedactor.java), [`PromptInjectionDetector.java`](src/main/java/com/example/springai/guardrail/PromptInjectionDetector.java) | `PiiRedactor` masks personal data (email/phone/ssn/ipv4/Luhn-checked card) from every answer before it leaves the API, including streamed chunks. `PromptInjectionDetector` screens uploaded documents for instruction-override patterns at ingest time — a retrieved chunk is injected as *context*, so an "ignore your instructions" document is an indirect injection. Knobs: `app.guardrails.redact-output`, `app.guardrails.block-injection-on-ingest`. |
| **Docs & demo UI** | [`OpenApiConfig.java`](src/main/java/com/example/springai/config/OpenApiConfig.java), [`index.html`](src/main/resources/static/index.html) | springdoc 2.8.17 (2.8.x is the Boot 3 line) and a dependency-free static page. |

---

## 🛡 Resilience and operations

| Concern | Behaviour | Knobs |
| :--- | :--- | :--- |
| **Socket timeouts** | `spring.http.client.connect-timeout: 5s`, `read-timeout: 90s`. Without them a wedged model process holds a request thread for the container default and a streaming browser tab sits on a half-dead connection. | `LLM_READ_TIMEOUT` |
| **Retries** | `spring.ai.retry.max-attempts: 2`, 500 ms initial backoff. A dead backend therefore costs ~10.6 s (connect timeout × 2) and one `503`, not a stack trace. | `spring.ai.retry.*` |
| **Failure contract** | `ResourceAccessException` / `TransientAiException` → `503` + `Retry-After: 5`; `NonTransientAiException` → `502`; blank or over-long parameters → `400`; unparseable model output → `422`; oversized upload → `413`. Every body carries `error`, `status`, `detail`, `requestId`. | — |
| **Concurrency ceiling** | A `Semaphore` caps simultaneous model calls at **4**; extra `/api/ai/**` requests are refused immediately with `503` + `Retry-After: 2` rather than queueing unseen. Streaming calls release their permit on `AsyncContext` completion, not when the filter returns. | `app.ai.max-concurrent-requests` |
| **LLM health** | `HealthIndicator` named `llm` sends a one-token prompt and reports `model`, `reply`, `latencyMs`, `probedAt` (or `error`/`cause` when DOWN). Results are cached so a load balancer polling health cannot itself become the dominant load on the model. | `app.health.llm.cache-ttl` (30s) |
| **Metrics** | `/actuator/metrics` exposes `gen_ai.client.token.usage`, `gen_ai.client.operation`, `gen_ai.client.operation.active` and the `app.ai.inflight` gauge. The demo-facing roll-up — requests, tokens, cache hit rate, latency p50/p95 — is `GET /api/ai/metrics/summary`. | `management.endpoints.web.exposure.include` |
| **Request logging** | `logs/endpoints.log` — one line per call with `requestId`; `logs/app.log` — the full log, same `requestId`. Docs, actuator and static assets are skipped so the multi-megabyte OpenAPI document does not drown the AI traffic. | `logging.level.ENDPOINT_ACCESS` |

---

## 📋 Logs

| File | Contents |
| :--- | :--- |
| `logs/endpoints.log` | One line per call: method, path + query, status, latency, client IP, truncated request/response bodies, `requestId`. |
| `logs/app.log` | Full application log, including the stack trace behind a failure, correlated by the same `requestId`. Streaming calls are recorded without bodies so SSE is not buffered. |

```bash
tail -f logs/endpoints.log                     # one line per call
grep <requestId> logs/app.log                  # the whole story for one request
grep SPRING-AI-TOOL logs/app.log               # proof a tool really executed
grep TOKEN_USAGE logs/app.log                  # tokens per call
grep -oE "resp=\{.{0,160}" logs/endpoints.log  # structured payloads
```

---

## 🖥 The demo page

`http://localhost:8080/` is a single dependency-free file
([`src/main/resources/static/index.html`](src/main/resources/static/index.html)) — no build step, no framework.
Eleven panels, one per capability: chat, templated prompt, streaming, chat memory (with **history** and **clear**
buttons that read/write the H2 store), tool calling (weather, order, multi, calculator, date/time, and the
`assistant` mode that exposes every tool plus the knowledge base), structured output, RAG query, vector search,
document upload, image analysis, and a **usage metrics** panel that renders `/api/ai/metrics/summary`. The
tool-calling panel also runs the agentic loop and draws its step trace; the assistant mode streams.

Every result panel shows the answer, the raw JSON body, and the status line `HTTP <code> · requestId <X-Request-Id>`,
so a failure can be taken straight to `grep <requestId> logs/app.log`. Streaming uses `EventSource`, which is the
same request path `curl -N` takes, so both hit the same concurrency ceiling. The RAG panel has an optional
conversation id; when a follow-up was resolved it also prints a one-line note naming the text that was searched
and the milliseconds the rewrite cost, because a citation list without that line invites the reader to assume the
question they typed is the question that was asked of the vector store.

---

## 🧪 Endpoint reference

Every request goes to port `8080`. The same shapes are browsable and executable at `http://localhost:8080/docs`.

### 1. Basic chat completion
`GET /api/ai/chat?message=...`

```bash
curl -i "http://localhost:8080/api/ai/chat?message=Explain+Spring+AI+in+one+sentence"
```
```json
{ "prompt": "Explain Spring AI in one sentence",
  "response": "Spring AI is a framework that enables developers to build applications using modern machine learning algorithms and frameworks while maintaining their flexibility and modularity." }
```

### 2. Prompt templating with dynamic parameters
`GET /api/ai/chat/template?role=...&topic=...`

```bash
curl -i "http://localhost:8080/api/ai/chat/template?role=Junior+Developer&topic=Database+Indexing"
```
```json
{ "role": "Junior Developer", "topic": "Database Indexing",
  "explanation": "Sure! Let's think about it like this:\n\nImagine you have a big box of Lego blocks. Each block is your individual Lego piece... These features are like little marks on the blocks that help us quickly find which specific block we'r…" }
```

*Sample bodies in §1–§4 are verbatim from `qwen2.5:0.5b-instruct` — grammar, invented facts and cut-offs
included. Point the app at another model and the same requests return different answers with no code change.*

### 3. Real-time token streaming (Server-Sent Events)
`GET /api/ai/chat/stream?message=...&conversationId=...`

```bash
curl -N "http://localhost:8080/api/ai/chat/stream?message=Write+a+poem+about+Java+21"
```

The stream is memory-aware: pass the same `conversationId` twice and the second answer can refer to the first.
The demo page consumes this with `EventSource`; the logging filter deliberately bypasses it so chunks are not
buffered until the stream closes.

### 4. Multi-turn memory, persisted
`GET /api/ai/chat/memory?message=...&conversationId=...`

```bash
curl "http://localhost:8080/api/ai/chat/memory?conversationId=session-42&message=My+name+is+Alice+and+I+am+a+Java+architect"
curl "http://localhost:8080/api/ai/chat/memory?conversationId=session-42&message=What+is+my+name+and+profession?"
```
```json
{ "conversationId": "session-42", "message": "What is my name and profession?",
  "response": "Your name is Alice, and your profession is a Java architect." }
```

Transcripts live in `./data/chat-memory` (H2), so they survive a restart. `conversationId` is capped at
**36 characters** because the shipped schema stores it in `VARCHAR(36)` and a longer value fails deep inside
JDBC.

The window is no longer a hard 6-message cut. While the stored history stays at or below the trigger
(`app.memory.summarization.trigger-messages`, default 8) the transcript is untouched, exactly as before. Past
it, everything older than the last few turns (`keep-recent-messages`, default 4) is folded into one summary
message — `"Summary of earlier conversation: …"` — that stays at the head, so a long conversation keeps its
opening instead of silently dropping it. The summary is redacted by `PiiRedactor` before it is stored, and a
summarizer call that fails or returns blank leaves the transcript as the window left it rather than replacing
history with nothing. Set `app.memory.summarization.enabled=false` for the plain window; either way
`GET /chat/memory/history` shows exactly what the advisor will inject.

- `GET /api/ai/chat/memory/history?conversationId=...` — read the stored window back:
  ```json
  { "conversationId": "session-42", "messageCount": 4,
    "messages": [ { "role": "user", "content": "My name is Alice..." },
                  { "role": "assistant", "content": "..." } ] }
  ```
- `DELETE /api/ai/chat/memory?conversationId=...` — drop the conversation: `{ "cleared": true, "conversationId": "session-42" }`

For a throwaway store, set `CHAT_MEMORY_JDBC_URL=jdbc:h2:mem:chat`.

### 5. Structured output (typed Java records)

`GET /api/ai/structured/movie?genre=...` → [`MovieRecommendation`](src/main/java/com/example/springai/dto/MovieRecommendation.java)

```bash
curl "http://localhost:8080/api/ai/structured/movie?genre=Cyberpunk"
```
```json
{ "title": "The Matrix", "releaseYear": 2016, "director": "Unknown",
  "genre": "Cyberpunk", "imdbRating": 8.7,
  "summary": "A dystopian future where the internet is a real thing, and the protagonist must navigate through a world of cybernetic beings.",
  "keyActors": ["Unknown"] }
```

Valid shape, invented facts — `The Matrix` is 1999 and the release year is what the 494M model reached for.
The endpoint guarantees the **type**; the **truth** belongs to the model (see §Known limits).

`POST /api/ai/structured/code-review` (body: plain-text source) → [`CodeReviewReport`](src/main/java/com/example/springai/dto/CodeReviewReport.java)

```bash
curl -X POST http://localhost:8080/api/ai/structured/code-review \
  -H "Content-Type: text/plain" \
  -d 'public User find(String id) { return db.query("SELECT * FROM users WHERE id=" + id); }'
```
```json
{ "language": "Java", "qualityScoreOutOf100": 95,
  "detectedVulnerabilities": [], "performanceTips": [],
  "suggestedRefactoring": "The code is well-structured and follows best practices.",
  "overallVerdict": "Good" }
```

The snippet is a textbook SQL injection and this model called it *Good*. Same caveat: the record shape, the
JSON mode and the 422-on-garbage contract are what the endpoint delivers; whether the review is worth anything
is a property of the model.

If the model returns JSON that does not fill the record, the endpoint retries once and then answers **422**
with the offending text, so the failure is diagnosable instead of a silent `null`:

```json
{ "error": "model_output_unparseable", "status": 422, "detail": "...",
  "conversionFailure": "...", "rawModelOutput": "...", "requestId": "9d9b3cd3" }
```

`conversionFailure` is present only when the text will not bind onto the record at all; a well-formed object
with a blank field is rejected by the endpoint's own completeness predicate, and then the body quotes the model
verbatim — this is a real response from `?genre=Cyberpunk`, so note that **the endpoint alternates between the
200 above and this** depending on whether the model leaves `director` empty:

```json
{ "error": "model_output_unparseable", "status": 422,
  "detail": "Model response did not contain a complete MovieRecommendation object",
  "requestId": "44dddd44",
  "rawModelOutput": "{ \"director\": \"\", \"genre\": \"Cyberpunk\", \"imdbRating\": 8.5, \"keyActors\": [\"Tom Holland\", \"Ewan McGregor\"], \"releaseYear\": 2017, \"summary\": \"A young man, played by Tom Holland, is recruited to infiltrate a cyberpunk society and uncover its secrets.\", \"title\": \"Inception\" }" }
```

### 6. Retrieval-Augmented Generation
`POST /api/ai/rag/query` — body `{ "question": "...", "filename": "...", "conversationId": "..." }`, where
`filename` is an optional metadata filter and `conversationId` is an optional id that turns the endpoint from
single-shot into a two-turn exchange (see *Follow-up questions* below). How many chunks get injected is
`app.rag.top-k`; it is **not** a per-request field, and an earlier revision of this record carried a `topK` that
the controller never read. That field has been deleted rather than wired up, so the published request schema now
matches the behaviour.

```bash
curl -X POST http://localhost:8080/api/ai/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question": "How much home office equipment stipend do employees get?"}'
```
```json
{ "question": "How much home office equipment stipend do employees get?",
  "resolvedQuestion": "How much home office equipment stipend do employees get?",
  "followUpResolved": false,
  "followUpOutcome": "NO_CONVERSATION",
  "rewriteTimeMs": 0,
  "answer": "Sorry, but I don't have any specific details about home office equipment stipends for employees in this particular context. …",
  "sourceDocuments": [
    { "filename": "company-policy.md", "score": 0.5937717910868997,
      "excerpt": "# Enterprise AI & Remote Work Policies 2026 ## 1. Remote Work & Equipment Employees are entitled to a $1,500 annual home..." },
    { "filename": "company-policy.md", "score": 0.326529790552246,
      "excerpt": "AWS, GCP, Spring certifications), and taking online training courses. ## 3. Leave Policy & Paid Time Off (PTO) - Standar..." }
  ],
  "groundingOutcome": "GROUNDED",
  "responseTimeMs": 1579 }
```

That response is the lesson, not a bug report: the chunk carrying *"$1,500 annual home office equipment
stipend"* is right there in `sourceDocuments` at 0.59, and the model still claims it has no details. On another
run the same request answers it correctly.

`sourceDocuments` comes from the advisor's own retrieval (`QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS`), so the
citations are literally the chunks that reached the prompt — not a second search that might have matched
differently. That also means an empty `sourceDocuments` is honest: nothing cleared
`app.rag.similarity-threshold` (0.2), so the model answered without context. Filtered queries used to hit this
often — `{"question": "failover window", "filename": "ops-runbook.md"}` scored the right chunk at 0.08, below
the floor, and returned no citations. Since hybrid retrieval shipped it no longer does: that chunk matches the
query's `failover` term on the BM25 leg, and `HybridVectorStore.eligible()` admits a chunk on a keyword hit
whatever its cosine, so the same request now returns the chunk (measured 0.070, still below the floor) and the
right answer. Run `/rag/search` with the same query and filename to see the score the floor *would* have
rejected — that contrast is the reason `/rag/search` exists beside `/rag/query`. With the coverage gate on
(the default), a request that retrieves nothing is caught before the answer model is asked, and one whose
candidates do not actually answer it is refused rather than answered from memory — best-effort, see the note
after the gate's own sample.
Scores are not bit-stable with a local embedder: the same query can read 0.1176 on one run and 0.1228
on the next, so treat the third decimal as noise. **`answer` is not reproducible either** — the refusal above is
one sample of it; the next run of the same request names the $1,500 correctly. Trust `sourceDocuments`, not the
prose.

#### Refusing instead of answering from memory

The paragraph above describes the failure this gate exists to stop: with an empty (or irrelevant)
`sourceDocuments`, the model answered anyway, from its own memory. Before the answer model is called,
`RagCoverageGate` retrieves the top `app.rag.crag.candidates` chunks and asks `LlmContextGrader` which of them
could answer the question. If none can, the search is broadened **once** — dropping the follow-up rewrite and
the filename filter, so the caller's own words are tried — and only then is the request refused:

```bash
curl -X POST http://localhost:8080/api/ai/rag/query -H "Content-Type: application/json" \
     -d '{"question": "Who won the 2022 World Cup?"}'
```
```json
{ "question": "Who won the 2022 World Cup?",
  "resolvedQuestion": "Who won the 2022 World Cup?",
  "followUpResolved": false, "followUpOutcome": "NO_CONVERSATION", "rewriteTimeMs": 0,
  "answer": "The knowledge base does not appear to cover this question, so I will not answer from memory. The closest passage was in company-policy.md (cosine 0.008), which is not close enough to answer from. Searched: \"Who won the 2022 World Cup?\".",
  "sourceDocuments": [ { "filename": "company-policy.md", "score": 0.008, "excerpt": "..." } ],
  "groundingOutcome": "REFUSED" }
```

`groundingOutcome` makes the decision explicit on every response: `GROUNDED` (the retrieved context covers the
question — the answer path is otherwise byte-identical to before this gate existed), `RE_QUERIED` (nothing
covered the original query but the broadened retry did, and `resolvedQuestion` is the broadened text that was
actually searched), `REFUSED` (answered without the model at all — the refusal quotes the rank-1 candidate's
cosine **and** filename whether or not it passed anything, and names what was searched), and `DISABLED`
(`app.rag.crag.enabled=false`). `app.rag.crag.candidates` (6) and `app.rag.crag.min-relevant` (1) tune it.

**The refusal is best-effort, and on this hardware that is a real limit.** The gate is deliberately
conservative — it fails **open**: a grader reply that names no usable passage is read as "no opinion" and every
candidate counts as relevant, because a false refusal (dropping a question the model *could* have answered)
costs more than a mistaken answer. The grader is itself a `qwen2.5-1.5b` that rarely emits the clean `NONE` the
prompt asks for, so the World Cup request above — `REFUSED` on one run — came back `GROUNDED` on the next two,
one of which answered *"the 2022 World Cup was won by the France national football team"* from the model's own
memory. Treat `REFUSED` as "the gate caught it this time", not a guarantee; the honest signal is that
`sourceDocuments` at 0.008 is not evidence for the answer beside it.

#### Follow-up questions, resolved before retrieval
Give the same request a `conversationId` and a second question can refer to the first:

```bash
curl -X POST http://localhost:8080/api/ai/rag/query -H "Content-Type: application/json" \
     -d '{"question":"What is the home office equipment stipend?","conversationId":"rag-floor-2"}'
curl -X POST http://localhost:8080/api/ai/rag/query -H "Content-Type: application/json" \
     -d '{"question":"how long do I have to submit that?","conversationId":"rag-floor-2"}'
```
```json
{ "question": "how long do I have to submit that?",
  "resolvedQuestion": "How long do you have to submit the home office equipment stipend?",
  "followUpResolved": true, "followUpOutcome": "REWRITTEN", "rewriteTimeMs": 325,
  "sourceDocuments": [ { "filename": "company-policy.md", "score": 0.47254126773022515, "excerpt": "..." } ] }
```

`resolvedQuestion` is the text that actually went into the vector search, and it is present on **every**
response — also when nothing was rewritten — because a citation list is only honest if the reader can see what
was searched. The same follow-up unresolved retrieved a chunk at 0.2185 that does **not** contain the answer
("within 30 days of purchase"); resolved, the chunk that does contain it came back at 0.4725. `GET /rag/search`
still takes the raw text, on purpose: it is the instrument that shows what an unresolved query does.

What that costs, measured on `qwen2.5:0.5b-instruct`: one extra model call of 325–595 ms and ~126–148 tokens,
against a `/rag/query` baseline of 1.1–2.1 s — call it +20–60 % on a turn that resolved. It is visible rather
than hidden: `rewriteTimeMs` in the response, a `[RAG-REWRITE]` line, and a second `TOKEN_USAGE` line per
request in `logs/app.log`.

`followUpOutcome` says why there was or wasn't a rewrite, so a silent fallback is impossible:
`DISABLED` (`app.rag.follow-up.enabled=false`), `NO_CONVERSATION` (no usable id), `EMPTY_MEMORY` (first turn),
`SKIPPED_BY_TRIGGER` (configured to need a pronoun, and this question has none), `PASSTHROUGH` (the model
declined, echoed, or produced text that failed validation), `REWRITTEN`. A model that is unreachable during the
rewrite is **not** a fallback: the request fails as `503 llm_backend_unreachable` like any other backend
failure, rather than quietly answering the pronoun.

RAG turns are written back into the same chat memory as `POST /api/ai/chat/memory`, so
`GET /api/ai/chat/memory/history?conversationId=rag-floor-2` shows them — the raw question, not the resolved
one. Two consequences worth knowing: **the memory window is shared**, so one RAG exchange spends two of the
messages the summary compacts, and the RAG *answer* is stored even when the request was refused, because the
next "what's the budget for that?" refers to the assistant's own prose.

`app.rag.follow-up.enabled` / `.max-history-turns` / `.max-query-chars` / `.require-trigger` (env:
`RAG_FOLLOW_UP_ENABLED`, `RAG_FOLLOW_UP_REQUIRE_TRIGGER`) configure it; `require-trigger=false` is the default
because a missed rewrite is invisible while a slow one is not. Why this is a service and not an advisor — the
bytecode that proves `MessageChatMemoryAdvisor` cannot help retrieval — is in
[`ARCHITECTURE_AND_METHODS.md`](ARCHITECTURE_AND_METHODS.md), and the rank-by-rank measurement that
decided whether to build it at all is in [`docs/SESSION-HANDOFF.md`](docs/SESSION-HANDOFF.md).

#### Raw vector similarity search
`GET /api/ai/rag/search?query=...&topK=...&filename=...`

```bash
curl "http://localhost:8080/api/ai/rag/search?query=conference+training+budget&topK=2"
```

This endpoint runs with **no** threshold, because an inspection tool that hides its empty results is useless:
when nothing matches, `totalResults: 0` arrives with a `note` explaining that the search floor cannot go below
0.0 cosine rather than implying the store is empty.

#### Index a document at runtime
`POST /api/ai/rag/documents` — `multipart/form-data`, part `file` (text/markdown/JSON/XML, ≤ 512 KB per file;
the multipart cap is 1 MB).

```bash
printf '# Ops Runbook\n\nDatabase failover is approved for 02:00-04:00 UTC only.\n' > target/ops-runbook.md
curl -X POST http://localhost:8080/api/ai/rag/documents -F "file=@target/ops-runbook.md"
curl "http://localhost:8080/api/ai/rag/search?query=failover&topK=2&filename=ops-runbook.md"
```
```json
{ "filename": "ops-runbook.md", "chunks": 1 }
```

Re-uploading a filename **replaces** its previous chunks, so iterating on a document does not double its
presence in every answer.

Uploads are guarded: a non-text content type is rejected `415`, and a document matching an instruction-override
pattern ("ignore your previous instructions", "reveal your system prompt", …) is rejected `422 request_rejected`
before it is indexed — a retrieved chunk is injected as context, so an uploaded injection targets every later
answer. Set `app.guardrails.block-injection-on-ingest=false` to index it anyway (the match is still logged).
Answers from `/rag/query` are passed through `PiiRedactor` before they are returned or stored.

#### What the knowledge base does on restart

The store is in memory while the process runs and is snapshotted to disk on every index change, so an upload
here survives a restart. Two files, one owner each:

```
data/rag/vector-store.json   # Spring AI's own serialization of SimpleVectorStore — opaque to this repo
data/rag/rag-store.json      # ours: { "version": 1, "checksums": { "<filename>": "<sha256>" } }
```

```bash
curl -X POST http://localhost:8080/api/ai/rag/documents -F "file=@target/ops-runbook.md"
# [RAG-STORE] Restored snapshot from .\data\rag (2 document checksum(s))
# [RAG-INGESTION] Kept persisted chunks for company-policy.md (unchanged)
curl "http://localhost:8080/api/ai/rag/search?query=failover&topK=2&filename=ops-runbook.md"   # still there
```

A restore needs **both** files; either missing or unreadable is a cold start plus a `WARN`, never a failed
boot. The checksums exist because `VectorStore` has no scan API, so "is this document already indexed?" cannot
otherwise be answered without calling the embedder — which is exactly the thing that may be offline at boot.
An unchanged shipped document is therefore not re-embedded, and a changed one is replaced by filename alone,
leaving uploads untouched.

Two deliberate edges:

- A snapshot that parses but answers no query is **discarded loudly**. Truncate `vector-store.json` to `{}` and
  the next boot says so:
  `[RAG-STORE] Discarding stale or partial vector snapshot in .\data\rag: 2 document(s) listed as indexed but
  the store answers no query`, then re-indexes the shipped documents. Without that probe the log would claim the
  chunks were kept while the knowledge base was empty.
- The integrity probe embeds a query, so it can only run when the embedder can. When it cannot, the snapshot is
  kept and the probe is skipped at `DEBUG`: re-indexing would fail for the same reason, and the store would only
  end up emptier.

`app.rag.store.enabled` / `.directory` / `.save-on-write` (env: `RAG_STORE_ENABLED`, `RAG_STORE_DIRECTORY`,
`RAG_STORE_SAVE_ON_WRITE`) control this; `enabled=false` writes nothing at all, which is what the eval harness
uses to stay reproducible from the repository alone.

### 7. Dynamic tool / function calling
The model inspects the prompt and decides whether to call a tool; Spring AI executes the Java method and feeds
the result back.

```bash
curl "http://localhost:8080/api/ai/tools/weather?prompt=What+is+the+weather+in+Tokyo+right+now?"
curl "http://localhost:8080/api/ai/tools/order?prompt=What+is+the+delivery+status+of+order+ORD-101?"
curl "http://localhost:8080/api/ai/tools/multi?prompt=Check+the+weather+in+London+and+the+shipping+status+of+order+ORD-103"
curl "http://localhost:8080/api/ai/tools/calculate?prompt=What+is+128+*+46+%2B+1024?"   # calculate
curl "http://localhost:8080/api/ai/tools/datetime?prompt=What+time+is+it+in+London?"     # getCurrentDateTime
curl "http://localhost:8080/api/ai/tools/assistant?prompt=How+many+days+of+leave+do+I+get,+times+3?"  # all five tools
```

`assistant` also accepts an optional `conversationId` (memory-aware), and `GET /api/ai/tools/assistant/stream`
is the same call as `text/event-stream`. `grep SPRING-AI-TOOL logs/app.log` proves a tool actually executed — a
plausible answer without that line was invented by the model, which a 494M-parameter model does roughly half
the time on the weather fixture.

### 8. Multimodal vision analysis
`GET /api/ai/vision/analyze?imageUrl=...&question=...`

```bash
curl -G "http://localhost:8080/api/ai/vision/analyze" \
  --data-urlencode "imageUrl=<public-image-url>" \
  --data-urlencode "question=What shapes and colors are in this picture?"
```

Pick the URL yourself — the endpoint takes no default asset. Requirements, learned the hard way:

- **Requires a vision model.** The chat model must have an image encoder (`llava`, `qwen2.5-vl`, `gpt-4o`, …).
  `qwen2.5:0.5b-instruct` has none and answers "I can't view images", or rejects the request with
  `502 llm_backend_rejected`.
- Direct file URL, not a resized thumbnail path — some CDNs, Wikimedia included, return **400** for sizes they
  do not publish and **403** to requests without a `User-Agent` (the server sends one).
- **Public internet addresses only.** The server fetches the caller's URL, so it refuses loopback, private,
  link-local and cloud-metadata ranges with `400 image_unavailable`.

### 9. Agentic multi-step loop
The model alternates between asking for a tool and reading its result until it answers — driven by
`AgenticLoopService` rather than Spring AI's internal loop, so every round is a visible step.

```bash
curl "http://localhost:8080/api/ai/tools/agent?prompt=How+many+days+of+annual+leave+do+I+get,+and+what+is+3+times+that+number?"
```

The response carries the final `answer` plus the ordered `steps` (each tool call with its arguments, and each
result), `modelCalls`, `toolCalls`, and `budgetExhausted` — true when `app.agent.max-steps` was reached and the
model was finally asked to answer without tools. The toolbox is `app.agent.tools`; naming a tool there is the
only thing that advertises it to the loop.

### 10. Usage metrics
One JSON object for the demo page, so what the process has served can be read without a metrics stack.

```bash
curl "http://localhost:8080/api/ai/metrics/summary"
```

It reports `requests` (total, errors, per-endpoint), `tokens` (prompt / completion / total), `cache`
(hits, misses, hitRate — the semantic-cache hit rate, counted where the lookup happens rather than inferred
from token deltas) and `latencyMs` (p50 / p95 / max / average over the last 512 calls). The same series are
exported to `/actuator/metrics` as `ai.requests.total`, `ai.tokens.total`, `ai.latency` and
`ai.cache.requests.total`.

---

## 🛠 Build, test, package

```bash
mvn -B test              # 122 tests — 119 run offline, 3 skipped, no model calls: standalone MockMvc +
                         # proxy-faked ChatClient, a stub embedder for the RAG store, @TempDir for the
                         # persistence tests
mvn -B test -Dapp.rag.eval=true   # runs the skipped ones too: the golden questions and the follow-ups
                                  # re-measured against the live all-minilm. Opt-in because it boots the
                                  # context and needs Ollama; without the property the class is skipped, which
                                  # is green, and no build file changes
mvn clean package -DskipTests
java -jar target/spring-ai-showcase-1.0.0-SNAPSHOT.jar
```

The eval dataset lives in [`src/test/resources/rag/golden-questions.json`](src/test/resources/rag/golden-questions.json):
15 questions, each naming the document it is filtered to and the **verbatim** chunk text that answers it. A
`match` row passes when a chunk carrying that marker ranks inside its budget; a `nomatch` row asserts that
nothing indexed carries it. Both tiers search with no threshold, so a score floor can only ever be what the
application is configured with — never what the metric quietly measured. The gates are on rank because scores
drift, and rank is what the two tiers actually agree on: **all 13 `match` rows land at rank 1** with the stub
embedder and with live `all-minilm` (MRR 1.000 both ways), while the two `nomatch` rows find no gold chunk — at
scores of 0.182 and **0.407** on one run, 0.190 and **0.405** on a re-measurement. That second number is the
finding worth carrying into any retrieval change here: an unanswerable question outranked three genuine matches
(`pii-prohibition` 0.345, `failover-from-runbook` 0.299, `acronym-pii` 0.158), so **a cosine floor cannot tell an
answerable question from an unanswerable one** — and `acronym-pii` ranks first at 0.158, under the app's 0.2
floor, which is the same chunk both correctly retrieved and correctly dropped.

A second dataset, [`src/test/resources/rag/follow-up-questions.json`](src/test/resources/rag/follow-up-questions.json),
pairs each raw follow-up with the antecedent turn and the resolved text. It is the one place the eval **does**
apply the configured floor, on purpose: a follow-up's failure mode is its gold chunk scoring 0.166 and being
dropped, which a threshold-free search cannot see. It lives in its own file rather than as extra rows in
`golden-questions.json` because a follow-up has to be able to expect a *miss* (`"expectRaw": "miss"`), and the
15 shipped rows have rank budgets calibrated by running them. Tier 1 asserts it against the stub — where
`how long do I have to submit that?` retrieves **nothing** at the floor and the resolved form retrieves its gold
chunk at 0.248 — and tier 2 prints the same rows on live vectors without gating them: unresolved rank 0 (gold
dropped) versus resolved rank 1 at 0.604, while the other two rows already rank 1 both ways.

Tier 2 talks to the real backend, so it needs the same environment as the app
(`OPENAI_BASE_URL=http://localhost:11434`, `OPENAI_EMBEDDING_MODEL=all-minilm`, …). Without it the class reports
**skipped, not failed** — boot's ingestion cannot reach the default base URL, the store stays empty, and the
reachability probe turns the run into a skip. Confusing, but the right direction for a mistake.

To stop a dev server on Windows: `netstat -ano | grep :8080` → `taskkill //PID <pid> //F`.

---

## ⚠️ Known limits of the local backend (not code bugs)

`qwen2.5:0.5b-instruct` is 494M parameters at Q4. Structure, error handling and streaming work; truthfulness is
the ceiling:

- Facts are invented inside valid JSON: `/structured/movie?genre=Cyberpunk` answered `The Matrix`, released
  **2016**, directed by `Unknown`; the same model put `$15,000` inside a `$2,500` budget in an earlier run.
- Judgement is as unreliable as recall: `/structured/code-review` scored a textbook SQL-injection snippet
  **95/100, verdict "Good"** (see §5) — the record it returns is well-formed either way.
- Long injected context is partially ignored: the right chunk reaches the prompt, but extraction questions can
  return a list of fragments.
- Tool calls fire at temperature 0.2 most of the time, not all of it; 0.0 stopped calling them, and an
  "answer only from the tool result" system prompt made the model describe the tool instead of using it.
- No image encoder, so vision needs a different model.

A larger local model (3B+ instruct, plus a vision model) or a hosted key moves all of them — the Termux
launcher's Qwen2.5-1.5B is already a step up from the figures quoted here.
