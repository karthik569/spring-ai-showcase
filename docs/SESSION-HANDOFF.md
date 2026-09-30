# Session handoff — spring-ai-showcase

Working notes so the next person (or model) can resume cold. Updated 2026-09-30 on Windows.
Not project documentation — see `README.md` and `ARCHITECTURE_AND_METHODS.md` for that.

**Start here:** Spring Boot **3.5.16** + Spring AI **1.1.8** (GA) on local Ollama (`qwen2.5:0.5b-instruct` chat
+ `all-minilm` embeddings). The upgrade, the new capabilities (RAG upload/filters/scores, persisted memory +
history, token metrics, demo UI, Swagger, timeouts/health/concurrency) are **done and verified live**, and
`README.md` / `ARCHITECTURE_AND_METHODS.md` now describe them. The change set is **committed on `main` as
`e1b9ade`** and **not pushed** — `origin/main` is one behind. Pushing needs an explicit ask.

## Environment (this machine)

- JDK Temurin 25.0.4 (pom targets Java 21 — compiles fine), Maven 3.8.6 at `C:\BuildTools`
- Spring Boot 3.5.16, Spring AI **1.1.8** from Maven Central (no milestone repository needed any more),
  springdoc-openapi 2.8.17, H2 for chat memory
- Ollama 0.20.7, CUDA GPU, **4 GB VRAM**; models cached: `qwen2.5:0.5b-instruct` (397 MB, Q4_K_M),
  `all-minilm` (45 MB, 384-dim embedder)
- `start-offline-llm.sh` / `start-spring-ai.sh` are **Termux-only** (`cd /sdcard/...`, `llama-server`) and do
  not work on Windows or macOS. They also reuse the chat weights as the embedder (`--embedding --pooling mean`),
  which is the one thing RAG must not do now that scores are surfaced.

## Run commands (verified this session)

```bash
ollama serve                          # or the full path to ollama.exe
OPENAI_API_KEY=ollama OPENAI_BASE_URL=http://localhost:11434 \
OPENAI_MODEL=qwen2.5:0.5b-instruct OPENAI_EMBEDDING_MODEL=all-minilm \
mvn -B spring-boot:run > run.log 2>&1 &
```

- `base-url` must **not** include `/v1` — Spring AI appends `v1/chat/completions`.
- `actuator/health` returns UP even with no backend: `RagDocumentIngestionService` wraps ingestion in
  try/catch, so a dead LLM never blocks startup — it logs `Document ingestion deferred or offline`. With the
  backend down, the **`llm` component** is DOWN and AI calls give `503` + `Retry-After: 5` in ~10.6 s
  (connect-timeout 5 s × 2 retry attempts).
- `mvn -B spring-boot:run` forks a JVM that **survives killing the Maven task**:
  `netstat -ano | grep :8080` → check `powershell Get-Process -Id <pid>` → `taskkill //PID <pid> //F`.
- Stop the app the same way. Scratch logs (`run*.log`, `ollama.log`, `pull.log`) are untracked noise from this
  session and can be deleted.

## What changed since the 2026-09-29 handoff

1. **Spring AI 1.0.0-M4 → 1.1.8, Boot 3.3.4 → 3.5.16.** Starter renamed
   (`spring-ai-starter-model-openai`); `SimpleVectorStore` and `QuestionAnswerAdvisor` live in their own
   modules now; the retry logger package changed; `Document.getScore()` exists, so `/rag/search` can show real
   cosine scores; and **since 1.1.6 `MessageChatMemoryAdvisor` throws when a call carries no conversation id** —
   which is why stateless endpoints use a separate `chatClient` bean.
2. **RAG**: `POST /api/ai/rag/documents` multipart upload (≤512 KB/file, 1 MB multipart cap, re-upload replaces
   by filename), `filename` metadata filter on both `/query` and `/search`, `RetrievedChunk` citations with
   scores taken from the advisor's own `RETRIEVED_DOCUMENTS`.
3. **Memory**: JDBC/H2-backed `MessageWindowChatMemory` (window 6) at `./data/chat-memory`, plus
   `GET /chat/memory/history` and `DELETE /chat/memory`. `conversationId` capped at **36 chars** because the
   schema column is `VARCHAR(36)`.
4. **Tokens**: `TokenUsageAdvisor` via `ChatClientCustomizer` → `TOKEN_USAGE` logger lines with the requestId;
   `gen_ai.client.token.usage` on the metrics endpoint.
5. **Docs & UI**: springdoc at `/docs` (302 → `/swagger-ui/index.html`), `OpenApiConfig`, and a dependency-free
   demo page at `/` with ten panels and `EventSource` streaming. `/` is the static welcome page — Swagger is
   deliberately *not* at the root.
6. **Resilience/ops**: `spring.http.client.connect-timeout/read-timeout`, `LlmHealthIndicator` (TTL-cached live
   probe), `AiConcurrencyLimitFilter` (semaphore of 4, `app.ai.inflight` gauge, async-aware release),
   `Retry-After` on the retryable 503s, and `GlobalExceptionHandler` extended to validation/upload/404-shaped
   failures.

## Traps that cost the most time

- **An advisor at `Ordered.LOWEST_PRECEDENCE` never runs.** Spring AI appends its terminal advisor
  (`ChatModelCallAdvisor`, order `2147483647`) to the request's own list and sorts stably, so a tie loses on
  insertion order and the advisor is silently never popped. `TokenUsageAdvisor` therefore uses
  `LOWEST_PRECEDENCE - 1`. Verified: `tokens prompt=37 completion=2 total=39`.
- **Every accessor on `CallResponseSpec` re-issues the request.** Pairing `entity()` with a later
  `chatResponse()` samples a *second* completion, so a 422 can report text that was never parsed. Use
  `responseEntity(...)`, which returns `ResponseEntity<ChatResponse, T>` from one call.
- **`initialize-schema: always` is safe on a second boot** — verified across three restarts against the same
  H2 file (the shipped script has no `IF NOT EXISTS`; Boot's continue-on-error swallows the duplicate table).
- **springdoc needs an explicit `consumes`** for `MultipartFile` parts or it documents `application/json` and
  Swagger UI cannot upload. Fixed on `/rag/documents`.
- **No git identity is configured on this machine.** `git commit` dies with `Author identity unknown`
  (`unable to auto-detect email address`). Commits need a one-off
  `git -c user.name="karthik569" -c user.email="sahukarikarteek@gmail.com" commit …` — the identity every
  existing commit carries. Editing `git config` is off-limits, so the override is per command. No git hooks are
  active in this repo (only `.sample` files), so a failed commit is never a hook problem.
- **Windows `curl.exe` cannot read Git Bash `/tmp` paths** — it returns `HTTP 000` with no body. Write scratch
  files under `target/` and use a relative path for `-F file=@…`.
- **Driving the browser without npm**: Node 22's global `WebSocket` + Edge `--remote-debugging-port=9222` speaks
  CDP directly; `--screenshot` needs an absolute Windows path. Scratch drivers live under `target/`.

## Verified behaviours (this session, live against Ollama)

- Endpoint sweep, all 200 with correct shapes: `/api/ai/chat`, `/chat/template`, `/chat/stream` (SSE),
  `/chat/memory` (2-turn recall), `/chat/memory/history`, `DELETE /chat/memory`, `/structured/movie`,
  `/structured/code-review`, `/rag/query` (grounded: "$1,500 annual home office equipment stipend",
  score 0.607 on the cited chunk), `/rag/search` (scores + empty-result `note`), `POST /rag/documents`,
  `/tools/order`, `/tools/weather`, `/tools/multi`, `/vision/analyze` (downloads fine; model has no encoder).
- Error contract: 400 blank question, 400 over-long conversationId, 400 private-address image URL, 413 oversized
  upload, 422 unparseable model output with `rawModelOutput`, 502 backend rejection, 503 + `Retry-After: 5`
  backend unreachable, 503 + `Retry-After: 2` over the concurrency cap (4×200 then 4×503 under load).
- Health: `UP` with `components.llm.details = {model: qwen2.5:0.5b-instruct, reply: "Ok.", latencyMs: 220}`;
  `DOWN` with the backend stopped.
- Metrics present: `gen_ai.client.operation`, `gen_ai.client.operation.active`, `gen_ai.client.token.usage`
  (tagged `chat`/`embedding`, `input`/`output`/`total`), `app.ai.inflight`.
- Demo UI exercised in a real browser: every panel returned, streaming rendered incrementally, requestId shown.
  Four UI bugs were found only by driving it (citations never rendering, empty raw dump for payloads without an
  `answer` field, stale citations after a failed follow-up, duplicated JSON block) — all fixed in
  `src/main/resources/static/index.html`.
- `mvn -B test` → **19 tests, 0 failures** across 5 classes (up from 12; `AiConcurrencyLimitFilterTest`,
  `RecordMappingTest` and the RAG upload cases in `ControllerIntegrationTest` are new).
- Both 413 paths verified live: a 600 KB file → `{"error":"request_rejected","status":413,"detail":"document is
  larger than 512 KB"}`, a 1.1 MB file → `document_too_large` from `MaxUploadSizeExceededException`.

## Deliberate non-decisions

- **No canned-response fallback.** "Graceful degradation" here means `503` + `Retry-After` + a DOWN `llm`
  health component, not a static sentence returned as if the model answered. Returning invented text would
  defeat the point of a showcase whose error bodies carry a `requestId`.
- **No `SimpleVectorStore` persistence.** Persisting uploads means either a hand-rolled save/load or a real
  vector DB; the in-memory store re-ingests from `classpath:/docs/*.md` at boot and says so in the README.
- **No forced `tool_choice`.** The M4-era claim in the old notes was unverifiable and was deleted; the endpoint
  keeps the empirical temperature note instead.
- **No rerank / query-rewriting advisors.** On a 494M model these add latency and damage recall rather than
  improving it.
- **No per-endpoint token attribution.** `TOKEN_USAGE` lines carry the requestId, which joins to
  `endpoints.log`; adding a tag per route would duplicate that join.
- **Antigravity / ChatGPT-Codex OAuth bridges stay out of this repo** — third-party bridges reuse login
  credentials against ToS. Keys stay in env; `application.yml` is tracked and reads `${OPENAI_API_KEY}`; the
  logging filter records bodies but never headers.

## Backend options (all config-only — the app speaks the OpenAI protocol)

| Option | Setup | Notes |
| :--- | :--- | :--- |
| OpenAI API key | `OPENAI_API_KEY=sk-...`, `OPENAI_MODEL=gpt-4o-mini`, `OPENAI_EMBEDDING_MODEL=text-embedding-3-small` | No code change; README §Quick start. Also lifts §Limits. |
| Gemini | `base-url=https://generativelanguage.googleapis.com/v1beta/openai` + `spring.ai.openai.chat.completions-path` / `spring.ai.openai.embedding.embeddings-path` overrides, plus a Gemini embedding model | Overrides existed in M4; **not re-verified against 1.1.8** — confirm both paths before documenting it in the README. |
| Ollama (current) | as in §Run commands | Offline and free; §Limits apply. |

## Limits of the local backend (not code bugs)

`qwen2.5:0.5b-instruct` is 494M parameters at Q4 on 4 GB VRAM. Facts get invented inside valid JSON; long
injected context is partially ignored; tool calls fire most of the time, not all; there is no image encoder, so
vision cannot work with this model. A larger local instruct model (+ a vision model) or a hosted key is what
moves them.

## Open work

1. **`e1b9ade` is committed on `main` and not pushed** (`git status -sb` shows `ahead 1`). Push only when asked.
   `data/` and the root `run*.log` / `ollama.log` / `pull.log` scratch files are gitignored, so they stayed out
   of the commit — deleting them is safe but unnecessary.
2. Optional: a small integration test for the upload → filter → answer path, so the filename filter is covered
   without a live embedder (the current tests fake `ChatClient`, so filter-expression correctness is only
   exercised against a real model).
3. Optional: `src/main/resources/docs/` holds one policy file; a second fixture would make filtered-RAG
   examples clearer in the README without asking users to upload first.
4. Consider a local model upgrade if factual answers matter more than staying offline.
5. `ARCHITECTURE_AND_METHODS.md` and `README.md` were rewritten to 1.1.8 on 2026-09-30. If the version moves
   again, the rows in its §8 migration table are the places that rot first.
