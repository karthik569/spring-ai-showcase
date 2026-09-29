# Session handoff — spring-ai-showcase

Working notes so the next person (or model) can resume cold. Updated 2026-09-29 on Windows.
Not project documentation — see `README.md` and `ARCHITECTURE_AND_METHODS.md` for that.

**Start here:** the app runs on local Ollama (`qwen2.5:0.5b-instruct` chat + `all-minilm` embeddings).
Request logging is done. All five confirmed defects are fixed in code; what remains is a model-capability
ceiling at 494M params (§Limits) and a decision about hosted backends (§Backend), which is no longer blocking.

## Environment (this machine)

- JDK Temurin 25.0.4 (pom targets Java 21 — compiles fine), Maven 3.8.6 at `C:\BuildTools`
- Spring Boot 3.3.4, Spring AI **1.0.0-M4**, `spring-ai-openai-spring-boot-starter` only
- Ollama 0.20.7, CUDA GPU, **4 GB VRAM**; models cached: `qwen2.5:0.5b-instruct` (397 MB, Q4_K_M),
  `all-minilm` (45 MB, 384-dim embedder, added this session)
- `start-offline-llm.sh` / `start-spring-ai.sh` are **Termux-only** (`cd /sdcard/...`, `llama-server`) and do not work on Windows or macOS

## Run commands (verified)

```bash
ollama serve                          # or the full path to ollama.exe
ollama pull all-minilm                # required for RAG; see defect 2 below
OPENAI_API_KEY=ollama OPENAI_BASE_URL=http://localhost:11434 \
OPENAI_MODEL=qwen2.5:0.5b-instruct OPENAI_EMBEDDING_MODEL=all-minilm \
mvn -B spring-boot:run
```

- `base-url` must **not** include `/v1` — Spring AI appends `v1/chat/completions`. Ollama's compatible
  endpoints `http://localhost:11434/v1/chat/completions` and `/v1/embeddings` both work.
- `actuator/health` returns UP even with no backend: `RagDocumentIngestionService` wraps ingestion in
  try/catch, so a dead LLM never blocks startup — it just logs `Document ingestion deferred or offline`.
- RAG now indexes **3 chunks** from `src/main/resources/docs/company-policy.md` (splitter at 120 tokens).
  With the old 400-token setting the whole file was one vector, so every question matched the same chunk
  and answers came from the wrong section.
- Stop: `netstat -ano | grep :8080` → `taskkill //PID <pid> //F`

## Logs

`logs/endpoints.log` — one line per call (method, path+query, status, ms, IP, truncated bodies).
`logs/app.log` — everything, same `requestId`. `run.log` / `ollama.log` — redirected console output.

```bash
tail -f logs/endpoints.log
grep <requestId> logs/app.log                  # full story for one call, incl. stack
grep SPRING-AI-TOOL logs/app.log               # proof a @Bean tool actually executed
grep -oE "resp=\{.{0,160}" logs/endpoints.log  # structured payloads — spot silent nulls
mvn -B test                                    # MockMvc suite, runs offline (no model needed)
```

## Logging layer (uncommitted)

- `config/EndpointLoggingFilter.java` — `OncePerRequestFilter` at `HIGHEST_PRECEDENCE`; MDC `requestId`
  (honours inbound `X-Request-Id`, echoes it on the response); bodies capped at 2000 chars, binary and
  multipart skipped; catches `ServletException|IOException|RuntimeException`, logs
  `FAILED <class>: <root message>` and rethrows; `copyBodyToResponse()` in `finally`.
- `resources/logback-spring.xml` — console + rolling `app.log` (14d/10MB) + `endpoints.log` (30d).
- `application.yml` — `logging.directory` (read via `springProperty`) and `ENDPOINT_ACCESS` level.
- `.gitignore` — `/logs/`, because rotated `.gz` names don't match the existing `*.log` rule.

Non-obvious decisions, keep them intact:

1. **SSE bypass.** `/stream` and `Accept: text/event-stream` skip `ContentCachingResponseWrapper`;
   buffering would stall every token until the stream closes. Verified chunks still arrive incrementally.
2. **`shouldNotFilterErrorDispatch() = true`** so a 500 logs exactly one line, not one per dispatch.
   Consequence: the container-rendered error JSON is not captured (`resp=<empty>`); the `FAILED` clause
   carries the cause instead.
3. **`%clr(...)` needs parentheses.** `%clr{%d{...}}{faint}` aborts startup with
   `All tokens consumed but was expecting "}"` — Logback errors here are fatal to the context.

## Defects and their status

1. **Structured output never saw the schema — FIXED.** In M4 `call().entity(Class)` only *converts* the
   response; `javap -c` over every `DefaultChatClient*` class in `spring-ai-core-1.0.0-M4.jar` shows **zero
   `getFormat()` references**. `StructuredOutputController` now sends `BeanOutputConverter.getFormat()` as a
   **prompt parameter** (StringTemplate uses braces as delimiters, so a schema must never be inlined as
   template text), restates the flat top-level key list after the schema, and requests
   `ResponseFormat.Type.JSON_OBJECT` at temperature 0.2. Residual: the model sometimes leaves a field empty,
   so the endpoint retries once and then answers **422** with `rawModelOutput` (plus `conversionFailure` when
   Jackson throws). Two traps met on the way:
   - **Every `CallResponseSpec` accessor re-issues the request.** `chatResponse()` → `doGetChatResponse()` →
     `doGetObservableChatResponse(request, null)`; nothing is cached, so pairing `entity()` with a later
     `chatResponse()` sampled a *second* completion and the 422 reported text that was never parsed (a
     complete record shown as the cause of its own rejection). The controller now takes one `chatResponse()`
     per attempt and converts with its own `BeanOutputConverter`: one request per attempt, truthful diagnostics.
   - M4's converter **either returns null or rethrows Jackson** (`InvalidDefinitionException: No fallback
     setter/field defined for creator property 'language'`) depending on how the output mismatches. Calling
     `entity()` directly therefore produced bare 500s; both paths now map to the same 422 contract.
2. **`app.rag.*` was dead config — FIXED.** `RagController` reads `app.rag.top-k` and
   `app.rag.similarity-threshold`; the threshold default dropped from 0.5 to **0.2** because all-minilm
   cosine for a relevant short question sits around 0.3–0.45, and 0.5 silently emptied the advisor's
   context (`/rag/query` answered "provide me with context" while `sourceDocuments` still listed the
   chunk — provenance search used no threshold, the advisor did). `/search` uses
   `withSimilarityThresholdAll()` plus a `note` field when nothing matched, because M4's
   `SimpleVectorStore` keeps the score in a private `Similarity` record and `Document` has **no
   `getScore()`** — scores cannot be surfaced without recomputing embeddings by hand.
3. **Stack-dump noise — FIXED.** `error/GlobalExceptionHandler` (`@RestControllerAdvice`) maps
   `StructuredOutputParseException`→422, `ImageDownloadException`→400, `ResourceAccessException`→503,
   `TransientAiException`→503, `NonTransientAiException`→502, each with `requestId`. `application.yml` also
   pins `org.springframework.ai.autoconfigure.retry: ERROR`, because that interceptor prints a full stack for
   every failed attempt. Measured: `app.log` went from 430 stack frames in 504 lines to **0 frames**, and a
   dead backend now costs 22 lines plus one `503 llm_backend_unreachable` body naming the cause.
   Deliberately absent: a catch-all `Exception` handler and custom 404/405, which would replace Spring's
   standard statuses with 500.
4. **Vision — FIXED except the model.** `MultimodalController` downloads with `RestClient` + `User-Agent`
   (Wikimedia returns 403 without one) into a `ByteArrayResource`, so the content type comes from the
   response headers; `imageUrl` is now **required** (no default asset) and rejects non-http(s) and any
   loopback/link-local/site-local/IPv6-ULA target, because the server fetches the caller's URL. Failures
   are no longer disguised as HTTP 200: download problems raise `ImageDownloadException`→400 and model
   errors reach the advice. The README route (`/describe`) was corrected to `/analyze` and the
   vision-model requirement is stated — `qwen2.5:0.5b-instruct` has no image encoder, so it answers
   "I can't view images" (200, because the backend accepted the request).
5. **Tool calling — library gap found; only partly addressable.** `OpenAiChatOptions.Builder.withToolChoice(...)`
   is **dead in M4**: scanning every class in `spring-ai-openai-1.0.0-M4.jar`, the string `toolChoice` appears
   only on `OpenAiApi$ChatCompletionRequest` (the wire field) and on `OpenAiChatOptions`/its builder — no code
   path reads `getToolChoice()`, and `javap -c org.springframework.ai.openai.OpenAiChatModel` finds zero
   references, so the value never reaches the request body. Posting `tool_choice:"required"` straight at
   `localhost:11434/v1/chat/completions` produced `tool_calls` 3/3, so the server honours it; the app cannot
   force it on this version. The endpoint therefore relies on the model choosing: temperature **0.2** gave
   `getOrderStatus` 3/3 with real fixture values and no placeholder leakage (`SHIPPED`, `Alice Johnson`,
   `$249.99`), while 0.0 stopped firing the tool and a "report only tool values" system prompt made the model
   talk *about* the tool instead of calling it. `withToolChoice` was removed as misleading; `/tools/weather`
   still invents conditions for London roughly half the time (answer says 21°C, fixture says 12°C Light Rain)
   and no `[SPRING-AI-TOOL]` line appears when it does. Same class of finding as defect 1: the M4 API surface
   promises more than it implements.

### Constraint when touching structured output again

`ControllerIntegrationTest` fakes `ChatClient` with `java.lang.reflect.Proxy`: `entity()` returns the
payload, `content()` returns `""` unless the payload is a `String`, every other chain method returns self.
So keep `.entity(...)` — rewriting to `.content()` + manual `BeanOutputConverter.convert()` breaks
`testStructuredOutputControllerStandalone`. `mvn -B test` is 12 tests, all offline.

### Endpoint sweep (last run)

200 and correct-shaped: `/api/ai/chat`, `/chat/template`, `/chat/stream` (SSE), `/chat/memory` (2-turn
recall), `/structured/movie`, `/structured/code-review` (catches `SQL Injection` in the sample snippet),
`/rag/query`, `/rag/search` (including the empty-result `note`), `/tools/weather`, `/tools/order`,
`/tools/multi` (refuses sometimes), `/vision/analyze` (downloads fine, model has no vision).
Error contract verified: 404 unmapped path, 400 missing required param, 422 incomplete record,
400 private-address image URL, 503 when the backend is stopped.

## Limits of the current local backend (not code bugs)

`qwen2.5:0.5b-instruct` is 494M parameters at Q4 on 4 GB VRAM. Observed, reproducible:

- Facts are invented inside valid JSON (`The Matrix`, 2016, "David Fincher"; `$15,000` for a `$2,500`
  budget). Structure is now right; truthfulness is not.
- Long injected context is partially ignored: the right chunk reaches the prompt, but extraction questions
  return a list of fragments.
- Tool calls fire most of the time with `tool_choice=required`, not all.
- No image encoder, so vision cannot work locally with this model.

A larger local model (e.g. a 3B instruct + a vision model) or a hosted key (§Backend) is what moves these.

## Backend options (no longer blocking — local mode is working)

Existing subscriptions cannot serve this app:

- **Antigravity SDK** — Python only, and requires a `GEMINI_API_KEY`; the IDE subscription is not usable
  for programmatic calls (see `google-antigravity/antigravity-sdk-python` issue #14; OAuth still unsupported).
- **ChatGPT/Codex plans (incl. Go)** — buy agent usage in Codex/ChatGPT, not API access; API billing is
  separate. Third-party OAuth bridges exist but reuse login credentials against ToS; not going in this repo.

All three viable options are config-only, because the app speaks the OpenAI protocol:

| Option | Setup | Notes |
| :--- | :--- | :--- |
| OpenAI API key | `OPENAI_API_KEY=sk-...`, `OPENAI_MODEL=gpt-4o-mini`, `OPENAI_EMBEDDING_MODEL=text-embedding-3-small` | No code change; README Mode 2 Option A. Prepaid credits. |
| Gemini API key | `base-url=https://generativelanguage.googleapis.com/v1beta/openai`, `spring.ai.openai.chat.completions-path=chat/completions`, `spring.ai.openai.embedding.embeddings-path=embeddings`, `model=gemini-3.8-flash`, embedding model `gemini-embedding-001` | Path overrides verified present in M4 (`OpenAiChatProperties.getCompletionsPath`, `OpenAiEmbeddingProperties.getEmbeddingsPath`). Free tier, no card. Also unblocks vision. |
| Ollama (current) | as in §Run commands | Offline and free; §Limits apply. |

Keep keys in env, never in `application.yml` (tracked). The logging filter records bodies but not headers,
so keys don't reach the logs.

## Open work

1. The change set (logging layer, advice, five controllers, yml, README, this doc) is committed on `main`
   as one commit — nothing is left uncommitted in the working tree.
2. `ARCHITECTURE_AND_METHODS.md` still describes the old RAG thresholds and the `/vision/describe` route.
3. `/tools/multi` has no forced tool choice (deliberate: it demonstrates model-dispatched selection);
   it inherits temperature 0.7 and refuses more often than the single-tool endpoints.
4. Consider a small local chat model upgrade if factual answers matter more than staying offline.
