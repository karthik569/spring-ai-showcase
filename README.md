# Spring AI Showcase 🤖

A Spring AI reference application built with **Java 21**, **Spring Boot 3.5.16** and **Spring AI 1.1.8** (GA).

It demonstrates **ChatClient**, **multi-turn chat memory persisted to a database**, **Retrieval-Augmented
Generation with document upload, metadata filters and real similarity scores**, **Dynamic Tool / Function
Calling**, **Structured Record Output Mapping**, **Multimodal Vision**, **reactive token streaming**, and the
operational layer a model-backed endpoint needs: **timeouts, a live LLM health check, a concurrency ceiling,
correlated request logging, and OpenAPI docs**.

Any OpenAI-protocol backend works — local Ollama, LM Studio, llama.cpp's server, or a hosted API key. Nothing
in the code is model-specific, but every sample output below was produced by `qwen2.5:0.5b-instruct`, so read
§Known limits before judging the answers.

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
| `http://localhost:8080/` | Demo UI — ten panels, one per capability, with a live `requestId` on every result |
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
work on Windows or macOS. They still start an OpenAI-compatible llama.cpp server on port 8081; note that
server's `--embedding` flag reuses the chat weights as the embedder, which is exactly the caveat above, so RAG
scores from that mode are not meaningful.

---

## 🏛 System Architecture

```
 Browser ── GET / ──────────► static/index.html   ten panels, EventSource for /chat/stream
 cURL   ── GET /docs ───────► Swagger UI          OpenAPI 3.1 document at /v3/api-docs
 cURL   ── GET|POST /api/ai/** ──► controllers

   request
      │
      ├─ EndpointLoggingFilter      requestId → MDC, one line per call in logs/endpoints.log
      ├─ AiConcurrencyLimitFilter   ≤4 model calls in flight, else 503 + Retry-After
      │
      ├─ ChatController            ┐
      ├─ RagController             │
      ├─ ToolCallingController     ├─► ChatClient ─► advisor chain (stable-sorted)
      ├─ StructuredOutputController│        │   TokenUsageAdvisor          LOWEST_PRECEDENCE - 1
      └─ MultimodalController      ┘        │   MessageChatMemoryAdvisor   window 6  ─► H2 ./data/chat-memory
                                            │   QuestionAnswerAdvisor      topK 4     ─► SimpleVectorStore ─► ./data/rag/*.json
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
| **Persisted multi-turn memory** | [`AiConfig.java`](src/main/java/com/example/springai/config/AiConfig.java) | `MessageWindowChatMemory` (6 messages) over the auto-configured JDBC repository and an H2 file database, so transcripts survive a restart. `/chat/memory/history` reads back exactly what the advisor will inject. |
| **Token accounting** | [`TokenUsageAdvisor.java`](src/main/java/com/example/springai/advisor/TokenUsageAdvisor.java) | Registered with a `ChatClientCustomizer` so *every* client logs prompt/completion/total to the `TOKEN_USAGE` logger; Spring AI also publishes `gen_ai.client.token.usage` to the metrics endpoint. |
| **RAG + upload + filters + scores** | [`RagController.java`](src/main/java/com/example/springai/controller/RagController.java) | `QuestionAnswerAdvisor` for grounded answers, `POST /rag/documents` for multipart indexing, a `filename` metadata filter, and `GET /rag/search` that surfaces each chunk's cosine score and explains zero matches. |
| **Knowledge base that survives a restart** | [`RagStorePersistence.java`](src/main/java/com/example/springai/service/RagStorePersistence.java) | `SimpleVectorStore.save`/`load` over two files in `./data/rag`, plus a per-filename SHA-256 manifest so an unchanged shipped document is not re-embedded and a truncated snapshot is discarded loudly instead of silently empty. |
| **Offline retrieval eval** | [`GoldenQuestionsTest.java`](src/test/java/com/example/springai/rag/GoldenQuestionsTest.java), [`LiveRetrievalComparisonTest.java`](src/test/java/com/example/springai/rag/LiveRetrievalComparisonTest.java) | 15 golden questions with verbatim answer markers, rank-based metrics, and a stub embedder that makes the real cosine/filter/threshold code assertable with Ollama switched off. |
| **Tool / Function calling** | [`ToolCallingController.java`](src/main/java/com/example/springai/controller/ToolCallingController.java) | `Function<Request, Response>` beans described with `@Description` ([`WeatherToolService`](src/main/java/com/example/springai/service/WeatherToolService.java), [`OrderToolService`](src/main/java/com/example/springai/service/OrderToolService.java)), bound per call with `.toolNames(...)`; the model decides whether to invoke them. |
| **Structured output** | [`StructuredOutputController.java`](src/main/java/com/example/springai/controller/StructuredOutputController.java) | `responseEntity(...)` parses into the record and keeps the raw completion; a flat answer shape is restated after the schema, an incomplete record retries once, then `422` with `rawModelOutput`. |
| **Multimodal vision** | [`MultimodalController.java`](src/main/java/com/example/springai/controller/MultimodalController.java) | Server-side download with a `User-Agent`, address-class screening, `Media` attachment on the prompt. |
| **Resilience & ops** | [`AiConcurrencyLimitFilter`](src/main/java/com/example/springai/config/AiConcurrencyLimitFilter.java), [`LlmHealthIndicator`](src/main/java/com/example/springai/health/LlmHealthIndicator.java), [`GlobalExceptionHandler`](src/main/java/com/example/springai/error/GlobalExceptionHandler.java) | HTTP timeouts, a cached live probe, a semaphore ceiling with `Retry-After`, and typed error bodies instead of container stack dumps. |
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
| **Metrics** | `/actuator/metrics` exposes `gen_ai.client.token.usage`, `gen_ai.client.operation`, `gen_ai.client.operation.active` and the `app.ai.inflight` gauge. | `management.endpoints.web.exposure.include` |
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
Ten panels, one per capability: chat, templated prompt, streaming, chat memory (with **history** and **clear**
buttons that read/write the H2 store), tool calling, structured output, RAG query, vector search, document
upload and image analysis.

Every result panel shows the answer, the raw JSON body, and the status line `HTTP <code> · requestId <X-Request-Id>`,
so a failure can be taken straight to `grep <requestId> logs/app.log`. Streaming uses `EventSource`, which is the
same request path `curl -N` takes; the panel re-renders as chunks arrive rather than at the end.

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
JDBC. Only the last **6 messages** are injected into a prompt.

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
`POST /api/ai/rag/query` — body `{ "question": "...", "filename": "..." }`, where `filename` is an optional
metadata filter. How many chunks get injected is `app.rag.top-k`; it is **not** a per-request field, and an
earlier revision of this record carried a `topK` that the controller never read. That field has been deleted
rather than wired up, so the published request schema now matches the behaviour.

```bash
curl -X POST http://localhost:8080/api/ai/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question": "How much home office equipment stipend do employees get?"}'
```
```json
{ "question": "How much home office equipment stipend do employees get?",
  "answer": "Sorry, but I don't have any specific details about home office equipment stipends for employees in this particular context. …",
  "sourceDocuments": [
    { "filename": "company-policy.md", "score": 0.5937717910868997,
      "excerpt": "# Enterprise AI & Remote Work Policies 2026 ## 1. Remote Work & Equipment Employees are entitled to a $1,500 annual home..." },
    { "filename": "company-policy.md", "score": 0.326529790552246,
      "excerpt": "AWS, GCP, Spring certifications), and taking online training courses. ## 3. Leave Policy & Paid Time Off (PTO) - Standar..." }
  ],
  "responseTimeMs": 1579 }
```

That response is the lesson, not a bug report: the chunk carrying *"$1,500 annual home office equipment
stipend"* is right there in `sourceDocuments` at 0.59, and the model still claims it has no details. On another
run the same request answers it correctly.

`sourceDocuments` comes from the advisor's own retrieval (`QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS`), so the
citations are literally the chunks that reached the prompt — not a second search that might have matched
differently. That also means an empty `sourceDocuments` is honest: nothing cleared
`app.rag.similarity-threshold` (0.2), so the model answered without context. Filtered queries hit this often —
`{"question": "failover window", "filename": "ops-runbook.md"}` scored the right chunk at 0.08 and correctly
returned no citations. Run `/rag/search` with the same query and filename to see the score the threshold
rejected. Scores are not bit-stable with a local embedder: the same query can read 0.1176 on one run and 0.1228
on the next, so treat the third decimal as noise. **`answer` is not reproducible either** — the refusal above is
one sample of it; the next run of the same request names the $1,500 correctly. Trust `sourceDocuments`, not the
prose.

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
```

`grep SPRING-AI-TOOL logs/app.log` proves a tool actually executed — a plausible answer without that line was
invented by the model, which a 494M-parameter model does roughly half the time on the weather fixture.

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

---

## 🛠 Build, test, package

```bash
mvn -B test              # 32 tests — 31 run offline, 1 skipped, no model calls: standalone MockMvc +
                         # proxy-faked ChatClient, a stub embedder for the RAG store, @TempDir for the
                         # persistence tests
mvn -B test -Dapp.rag.eval=true   # runs the skipped one too: the golden questions re-measured against the live
                                  # all-minilm. Opt-in because it boots the context and needs Ollama; without
                                  # the property the class is skipped, which is green, and no build file changes
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

A larger local model (3B+ instruct, plus a vision model) or a hosted key moves all of them.
