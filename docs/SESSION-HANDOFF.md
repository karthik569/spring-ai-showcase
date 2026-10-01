# Session handoff — spring-ai-showcase

Working notes so the next person (or model) can resume cold. Last updated 2026-10-01, on Termux/PRoot arm64
(the earlier sections below were written on Windows and are kept as history).
Not project documentation — see `docs/GETTING_STARTED.md` (on-ramp), `README.md` (reference) and
`ARCHITECTURE_AND_METHODS.md` (the why) for that.

**Start here:** Spring Boot **3.5.16** + Spring AI **1.1.8** (GA). Two run modes, both verified:
Ollama (`qwen2.5:0.5b-instruct` + `all-minilm`, what the README samples were measured on) and the shipped
llama.cpp launcher (`start-offline-llm.sh`: **Qwen2.5-1.5B** on 8081, **all-minilm** on 8082 via
`app.embedding.base-url`). The upgrade and the shipped capabilities are **done and verified live**, and
`README.md` / `ARCHITECTURE_AND_METHODS.md` / `docs/GETTING_STARTED.md` describe them. Rather than restating
push state here (it went stale twice), check it: `git log --oneline origin/main..HEAD` lists whatever is still
local.

## Environment (this machine)

- JDK Temurin 25.0.4 (pom targets Java 21 — compiles fine), Maven 3.8.6 at `C:\BuildTools`
- Spring Boot 3.5.16, Spring AI **1.1.8** from Maven Central (no milestone repository needed any more),
  springdoc-openapi 2.8.17, H2 for chat memory
- Ollama 0.20.7, CUDA GPU, **4 GB VRAM**; models cached: `qwen2.5:0.5b-instruct` (397 MB, Q4_K_M),
  `all-minilm` (45 MB, 384-dim embedder)
- On Windows the scripts do not run (`cd /sdcard/...`, `llama-server`). On Termux they now start **two**
  servers — chat (Qwen2.5-1.5B) on 8081 and a dedicated all-minilm embedder on 8082 — so the vectorizer is a
  real embedding model, not the chat weights. (`start-spring-ai.sh` sets `OPENAI_EMBEDDING_BASE_URL=8082`.)

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

## What changed later on 2026-09-30 (the RAG round)

1. **The knowledge base survives a restart.** `RagStorePersistence` snapshots `SimpleVectorStore` to
   `./data/rag/vector-store.json` and keeps `{"version":1,"checksums":{filename:sha256}}` in
   `rag-store.json`; boot is restore → checksum-keyed refresh → one save, so `POST /rag/documents` work is no
   longer lost and an unchanged shipped document is not re-embedded. A truncated snapshot is caught by an
   integrity probe and re-indexed **loudly** instead of leaving an empty store that logs success.
2. **Retrieval is regression-testable offline.** `src/test/java/…/rag/` holds `StubEmbeddingModel`,
   `RetrievalMetrics`, `GoldenQuestions` (shared by both tiers), `GoldenQuestionsTest` and
   `LiveRetrievalComparisonTest`, with 15 rows in `src/test/resources/rag/golden-questions.json` and a test-only
   runbook under `src/test/resources/rag/corpus/`. Tier 1 runs in `mvn -B test` with the model off; tier 2 is
   `-Dapp.rag.eval=true` and *reports* rather than asserts.
3. **`RagQueryRequest.topK` is gone** (see §Traps) along with `index.html` sending it and the README claiming it
   worked.
4. **Not built (as of this date):** hybrid retrieval (BM25 + RRF) and the grounding gate. Both were designed in
   the approved plan, and §Open work item 6 records the two measurements that undercut their premises.
   *(Superseded: hybrid retrieval shipped on 2026-10-01 — the next section — and groundedness became a tier-3
   judge, `GroundednessJudgeTest`.)*
5. **Still deliberately untouched:** `pom.xml` (so tier 2 is opted in by system property, not excluded by a
   surefire tag), `src/main/resources/docs/` (one shipped fixture), and any real vector store.

## What changed on 2026-10-01 (the enhancements round — on Termux/Linux, not Windows)

Four showcase extensions, chosen from a menu and all verified live against the local llama.cpp servers
(chat `qwen2.5-1.5b` on 8081, `all-minilm` on 8082):

1. **Semantic response cache** — `SemanticCacheAdvisor`, the outermost `CallAdvisor`; a paraphrase of an
   answered question is replayed from a vector index of prior questions without a model call. Skips requests
   whose answer depends on more than the prompt (tools / conversation / media / output schema). `app.cache.*`,
   default threshold 0.95.
2. **Hybrid retrieval** — `HybridVectorStore` (`@Primary` `VectorStore` decorator) fusing the cosine ranking
   with `KeywordIndex` (BM25) via reciprocal rank fusion. `RAG_HYBRID_ENABLED`, `candidate-pool`, `rrf-k`.
3. **Agentic multi-step loop** — `AgenticLoopService` + `GET /api/ai/tools/agent`; the loop is driven by hand
   (model call → `ToolCallingManager.executeToolCalls` → repeat) so each round is a returned `Step`, bounded by
   `app.agent.max-steps`, toolbox `app.agent.tools`.
4. **Observability/cost** — `AiMetrics` + `AiRequestMetricsFilter` + `MetricsController` → `GET
   /api/ai/metrics/summary` (requests, tokens, cache hit rate, latency p50/p95), also exported as Micrometer
   counters. New demo panel + agent trace in `index.html`.

**The bug worth recording (found only live, never by the suite).** The cache never hit at 0.95: two identical
`/api/ai/chat` calls produced identical keys yet the lookup scored **0.794**. Root cause, confirmed by
`javap` on `SimpleVectorStore`: `doAdd` embeds a Document via `EmbeddingModel.embed(Document)` →
`OpenAiEmbeddingModel.getFormattedContent(MetadataMode.EMBED)` (metadata folded in), while `doSimilaritySearch`
embeds the bare query `String`. So the stored question was embedded as "question + answer + timestamp" and could
not match itself. Fixed by giving the stored `Document` a `ContentFormatter` returning only its text. The reason
the tests missed it: `StubEmbeddingModel.embed(Document)` uses `getText()`, so the shared stub never modelled
the asymmetry. `SemanticCacheAdvisorTest` now uses a `MetadataInclusiveEmbeddingModel` that does — reverting the
formatter turns two of its tests red. Live re-check: cold 11.12 s → cached 0.167 s, `cache:{hits:1,misses:1}`.

**Environment traps added this session (Termux/PRoot arm64):**

- **The `Edit`/`Write` tools do not bump mtime on the `/mnt/sdcard` fuse mount**, so Maven's incremental
  compiler skips the edited file. `touch` every changed file before `mvn`, or the build silently runs old code.
- **`mvn clean` is unavailable offline** (maven-clean-plugin not in `~/.m2`); rely on the `touch` + recompile.
- **`pkill -f` / `pgrep -f` self-match** when the pattern appears in the invoking shell's own argv and kill the
  shell (exit 143). Use `pgrep -x java` and filter `/proc/$pid/cmdline`.
- **A second app instance cannot share the H2 file** (`./data/chat-memory` is exclusively locked while the app
  runs). A smoke instance must override `--spring.datasource.url=jdbc:h2:mem:...`.
- **The agent's ambient env leaks into a spawned app**: `OPENAI_BASE_URL=https://tokenharbor.ai/v1` and friends
  must be scrubbed with `env -u` and the local `base-url`/dummy key set explicitly, or requests 404 upstream.

## Phase 0, measured before the conversation-aware RAG code existed (2026-09-30)

The premise of query rewriting is that an unresolved pronoun retrieves the wrong chunk. On this 3-chunk corpus
that premise is **mostly false**, and what survives the measurement is more specific. Chunk **A** = title + §1
equipment (`$1,500 … stipend`, `within 30 days of purchase`) + §2 heading; chunk **B** = rest of §2
(`$2,500 annual budget`, `AWS, GCP, Spring certifications`) + §3 leave (`16 weeks of fully paid leave`).

**Tier 1 — stub embedder, real splitter/store, 4 chunks (3 policy + the test runbook), topK 5, no floor.**
Gold marker in parentheses; `firstHitRank`:

| follow-up | raw | resolved | gold cosine raw → resolved |
| :--- | :--- | :--- | :--- |
| `and what's the budget for that?` (`$2,500 annual budget`) | rank 2 | rank 2 | 0.226 → 0.315 |
| `how long do I have to submit that?` (`within 30 days of purchase`) | rank 2 | rank 2 | **0.070 → 0.239** |
| `and for caregivers?` (`16 weeks of fully paid leave`) | rank 1 | rank 1 | 0.380 → 0.446 |

**Tier 2 — live `all-minilm`, the 3 shipped chunks, `/rag/search` only (no model call, no floor, topK 5):**

| follow-up | raw ranking | resolved ranking |
| :--- | :--- | :--- |
| `and what's the budget for that?` | **A 0.455 rank 1**, B 0.186, PII 0.065 | A 0.577, B 0.255 |
| `how long do I have to submit that?` | **B 0.219 rank 1, A 0.166 rank 2** | **A 0.588 rank 1**, B 0.305, PII 0.178 |
| `and for caregivers?` | **B 0.278 rank 1**, A 0.192, PII 0.069 | B 0.469, A 0.330, PII 0.017 |

So **two of the three follow-ups already retrieve their gold chunk at rank 1 unresolved.** Rewriting is not a
rank fix on this corpus. The middle row is the real defect, and it is about the *floor*, not the rank: with
`app.rag.similarity-threshold: 0.2` the raw form keeps only B (0.2185) and **drops A (0.166)**, so the answer is
generated from the leave/training chunk. Reproduced end-to-end:

```
POST /api/ai/rag/query {"question":"how long do I have to submit that?"}
→ 200, sourceDocuments=[B @ 0.2185 "AWS, GCP, Spring certifications), and taking online traini…"]
→ answer: "Based on the given information, I cannot provide specific details about the leave policy or PTO…"
```

A confident-looking citation list for a document that cannot answer the question — Step 3's lesson with a new
cause. The same text phrased standalone retrieves A first (0.588) and cites it. Under the stub the same row is
starker: raw scores 0.072 max, so **0 chunks clear 0.2 and the answer is pure parametric memory**; resolved
puts gold at 0.239 and it is retrieved. Rewriting also lifts every gold cosine here (+0.122 / +0.422 / +0.191),
which is what keeps a marginal follow-up above the floor at all.

**Cost, measured rather than argued:** an isolated rewrite call (the prompt below sent through `GET /api/ai/chat`)
took **0.21 s – 0.45 s**; the baseline `POST /rag/query` was 710–1164 ms `responseTimeMs`. Budget roughly
+20–60 % on a turn that gets resolved, and ~0 % on the turns skipped by trigger or by an empty memory.

**How `qwen2.5:0.5b-instruct` actually behaves on the rewrite prompt** — this, not the docs, set the validation
rules:

| input | model output | consequence |
| :--- | :--- | :--- |
| `and what about travel?` | the input echoed verbatim | caught by the equals-original rule → `PASSTHROUGH` |
| `how long do I have to submit that?` | **`to SUBMIT that?`** | *not* equal to the original, so a naive port searches a fragment → needs the still-contains-a-demonstrative guard |
| `What is the AI tool usage policy?` (already standalone; prompt says reply `SAME`) | `What is the AI (Artificial Intelligence) tool usage policy?` | the model does **not** reliably signal "nothing to do"; the Java-side rules decide, never the model's self-report |

**Two things the measurement removed from the design.** (1) `NoOpTemplateRenderer` is unnecessary: `POST
/rag/query {"question":"What is the {policy} for conferences?"}` returned **200** with the braces intact, and
`/api/ai/chat?message=explain {foo} and {bar}` likewise, because `.user(String)` does not run the ST4 renderer —
only `.user(u -> u.text(...).param(...))` does. (2) Resolution is deliberately **not** applied to
`GET /rag/search`: that endpoint exists to show the raw-query behaviour, and resolving there would hide exactly
the evidence above.

## Conversation-aware RAG shipped, and the running app corrected two things (2026-09-30)

Built as planned: `service/RagQueryResolver.java` (not an advisor — §2 of the architecture doc explains why), a
`@Qualifier("ragQueryRewriter")` `ChatClient` bean with no default advisors, `support/ConversationIds.java`
shared with `ChatController`, `conversationId` on the request, `resolvedQuestion` / `followUpResolved` /
`followUpOutcome` / `rewriteTimeMs` on the response, a `#ragConv` field and a "searched:" note on the demo page,
and `app.rag.follow-up.{enabled,max-history-turns,max-query-chars,require-trigger}`. `pom.xml` untouched.
`mvn -B test` is now **59 tests, 57 run offline, 2 skipped** (up from 32/31/1).

**The over-rejection bug, found only by driving the live app.** Phase 0 predicted the validation rules from
model outputs; it could not predict this. On the floor row, `qwen2.5:0.5b-instruct` returned

```
[RAG-REWRITE] Discarded 'How long does it take to submit the home office equipment stipend?';
              still carries an unresolved demonstrative
```

— a *correct* substitution that kept a placeholder **it**, thrown away by the "still contains a demonstrative"
guard. The request then cited chunk B at 0.2185 (which does not contain the 30-day rule) and answered "I don't
have enough context". The guard now fires only when the rewrite also **borrowed no content word from the
conversation**, which keeps this rewrite and still discards `to SUBMIT that?` (adds nothing) and an invented
noun like `that invoice?` (not in the transcript). Lesson for the next heuristic here: *over-rejection is not a
neutral fallback when the fallback is the bug.* After the fix, the same live turn:

```
POST {"question":"how long do I have to submit that?","conversationId":"rag-floor-2"}
→ followUpOutcome REWRITTEN, rewriteTimeMs 325
→ resolvedQuestion "How long do you have to submit the home office equipment stipend?"
→ sourceDocuments[0] = company-policy.md @ 0.4725 — the chunk that DOES contain "within 30 days of purchase"
```

Retrieval is now provably right; the 0.5B model still answered "the context does not mention…", which is the
deferred grounding gate (D), not this feature.

**Everything else in the plan's verification block, run against the live app:**

| check | result |
| :--- | :--- |
| two turns, `conversationId=rag-verify-1` | turn 1 `EMPTY_MEMORY` + `resolvedQuestion == question`; turn 2 `REWRITTEN` 595 ms, named the thing instead of "that" |
| `GET /chat/memory/history?conversationId=rag-verify-1` | 4 messages — both RAG turns, storing the **raw** question |
| Ollama killed between turns | `503 llm_backend_unreachable` (`ClosedChannelException`), **not** a degraded 200, and the history stayed at 4 messages — nothing half-written |
| `RAG_FOLLOW_UP_ENABLED=false` | `DISABLED`, `rewriteTimeMs=0`, `resolvedQuestion` echoes the input, identical with and without a conversation id, and `remember()` writes nothing |
| `conversationId` of 40 chars | `400 invalid_request — conversationId is limited to 36 characters` (from `@Size`; `/v3/api-docs` advertises `maxLength: 36`) |
| the second model call is visible | one requestId carries **two** `TOKEN_USAGE` lines (rewrite 139+9 / 111+15 tokens) plus the `[RAG-REWRITE]` line |
| tier 2, live `all-minilm`, follow-up table | `follow-up-stipend-window` raw **rank 0, gold dropped** → resolved rank 1 @ 0.604; the other two rows rank 1 both ways (0.473→0.600, 0.279→0.464). Report-only, as designed |

**Not verified:** the RAG panel was never rendered in a browser this round — this machine has no headless browser
and installing one would break the zero-new-dependency rule, and the earlier "demo UI exercised in a real browser"
line above predates this change. What was done instead: the served HTML contains `#ragConv` and the note element,
the inline script passes `node --check`, and every field the handler reads (`resolvedQuestion`,
`followUpResolved`, `followUpOutcome`, `rewriteTimeMs`) was confirmed in live responses. A human click-through of
the panel is still owed.

**Tier 2 invocation, corrected:** `mvn -B test -Dapp.rag.eval=true` *does* reach the fork, but if the Maven shell
lacks `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_EMBEDDING_MODEL`, boot indexes nothing and both methods abort
through `OllamaReachable` as `Skipped: 2` with no reason in the `.txt` report (the message is only in the XML).
That looks identical to the property not taking effect; it is not. Export the app's env into the shell that runs
Maven.

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
- **Local embedding scores drift between runs.** The identical query/document pair read `0.1176` and then
  `0.1228` on two calls to `/rag/search`. Ollama's embedder is not bit-stable, so never write a test or a doc
  that asserts a third decimal.
- **Both `./data/rag` files are needed to restore, and the pair is not portable.** `vector-store.json` is
  Spring AI's format and `rag-store.json` is the manifest; one without the other is a cold start by design, and
  `restore()` never throws. Deleting the directory is the reset button. The three knobs are
  `app.rag.store.{enabled,directory,save-on-write}`, each with an env form
  (`RAG_STORE_ENABLED`, `RAG_STORE_DIRECTORY`, `RAG_STORE_SAVE_ON_WRITE`) or a
  `--app.rag.store.directory=…` argument. Tier 2 needs `-Dapp.rag.eval=true` to reach the **forked** test JVM —
  surefire propagates system properties, so the plain flag works (verified); `-DargLine=-Dapp.rag.eval=true` is
  the fallback if a class reports skipped when you expected it to run. Tier 2 also needs the **same `OPENAI_*`
  env as the app** — reproduced: with `OPENAI_BASE_URL` unset, boot aimed at the default `localhost:8081`,
  ingestion logged `Document ingestion deferred or offline`, and the run reported `Skipped: 1` while looking
  exactly like an unmet `-D` flag.
- **A filename collision is now permanent.** Uploading `company-policy.md` evicts the shipped chunks, and after a
  restart persistence restores the upload, the checksum no longer matches the manifest, and boot re-indexes 3
  chunks over the top — the uploaded text is gone (verified: 3 → 1 → restart → 3). Before persistence this was
  also true, but the next boot silently repaired it.
- **`POST /rag/query` no longer accepts `topK`.** The field was validated, sent by `index.html`, documented in
  the README — and never read by the controller. Chunk count is `app.rag.top-k` only. An old client posting
  `{"question":…, "topK":2}` still gets 200 (unknown fields are ignored), so nothing tells you the parameter is
  inert; check `/v3/api-docs` (schema `[question, filename]`) rather than a status code.
- **Try a config change without editing tracked YAML:**
  `mvn -B spring-boot:run -Dspring-boot.run.arguments=--app.rag.similarity-threshold=0.4`. Verified: the `pto`
  query goes from one citation (≈0.32) to zero at 0.4 — a clean demo of a threshold silently un-grounding an
  answer, and the basis of `docs/GETTING_STARTED.md` §Step 3.
- **Both the app and Ollama can die silently** between sessions (the CLI background task does not outlive the
  session, and `ollama.exe` was gone too). Symptoms: `curl` → `HTTP 000` / exit 7 on 8080 or 11434. Restart
  `ollama serve` first, then the app. Since persistence landed, a backend that was down at boot is much less
  damaging: `SimpleVectorStore.load` is pure JSON and the integrity probe swallows the embedder's absence, so
  `./data/rag` still restores into RAM and the uploaded documents are not lost by a boot that happened without a
  model. Queries still embed their input, so nothing answers until the embedder is up — what changed is that the
  knowledge base survives the outage instead of being re-embedded or emptied. What still needs a restart is a
  **first** run with no snapshot at all: the store is empty, boot logs
  `Document ingestion deferred or offline`, and `/rag/query` answers fluently from the model's own memory until
  you start the app again with the embedder up.
- **A second app instance does not fail on the port — it fails on the H2 file lock**, and the message is
  misleading. Reproduced: `Failed to determine DatabaseDriver` → `CannotGetJdbcConnectionException` →
  `The file is locked: …/data/chat-memory.mv.db`. It reads like a datasource misconfiguration; it only means an
  instance is already running. Check `netstat -ano | grep ":8080 .*LISTENING"` (then
  `powershell Get-Process -Id <pid>` to confirm it is java) before touching `spring.datasource`.
- **Windows `curl.exe` cannot read Git Bash `/tmp` paths** — it returns `HTTP 000` with no body. Write scratch
  files under `target/` and use a relative path for `-F file=@…`.
- **Driving the browser without npm**: Node 22's global `WebSocket` + Edge `--remote-debugging-port=9222` speaks
  CDP directly; `--screenshot` needs an absolute Windows path. Scratch drivers live under `target/`.

## Verified behaviours (this session, live against Ollama)

- Endpoint sweep, all 200 with correct shapes *on that pass* (two of them alternate — see the re-verification
  block below): `/api/ai/chat`, `/chat/template`, `/chat/stream` (SSE),
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
- `mvn -B test` → **59 tests, 57 run and 2 skipped, 0 failures** across 10 classes. The skips are the two methods
  of `LiveRetrievalComparisonTest`, gated on `-Dapp.rag.eval=true` rather than a tag, because a tag needs
  surefire config and the build file is off-limits this round. New since the RAG round:
  `RagStorePersistenceTest` (9), `GoldenQuestionsTest` (2), `StubEmbeddingModel` / `RetrievalMetrics` /
  `GoldenQuestions` / `OllamaReachable` (helpers, not tests), plus `LiveRetrievalComparisonTest` (2, opt-in).
  New with conversation-aware RAG: `RagQueryResolverTest` (22), `FollowUpRetrievalTest` (3),
  `RecordingChatClient` (helper) and 2 more cases in `ControllerIntegrationTest` (10 total).
  Verified green with **Ollama switched off** — that is the point of the stub embedder tier.
- Both 413 paths verified live: a 600 KB file → `{"error":"request_rejected","status":413,"detail":"document is
  larger than 512 KB"}`, a 1.1 MB file → `document_too_large` from `MaxUploadSizeExceededException`.

Re-verified while writing `docs/GETTING_STARTED.md` (same day, later run) — two behaviours worth knowing before
you document any sample output:

- **`/rag/query` prose is not reproducible; its citation list mostly is.** The stipend question answered
  "Employees are entitled to a $1,500 annual home office equipment stipend." on one call and "You haven't
  mentioned any specific instance of a home office equipment stipend…" on the next, **with the same chunk cited
  at 0.60**. Same model, same prompt. So never assert an answer string in a doc or a test — assert
  `sourceDocuments`.
- **`/structured/movie` alternates between 200 and 422.** 200 carries invented-but-well-shaped facts
  (`The Matrix`, 2016); 422 appears when the model leaves `director` empty, and its `rawModelOutput` then shows
  `"title": "Inception", "releaseYear": 2017`. Both are correct behaviour of `validated(...)` — quote them as a
  pair, not as one canonical response.
- Live numbers from that pass: `pto` search top 0.3285 / second 0.1992, sentence query 0.5629, uploaded runbook
  chunk 0.1118 (below the 0.2 threshold → `sourceDocuments: []`), `/chat/memory` recall answered `Miso.` and on
  a second conversation `Misto.`, health details `{model, reply, latencyMs, probedAt}`, 8-request burst →
  `503 503 503 503 200 200 200 200`.
- `/v3/api-docs` reports **15** operations — don't quote a count in the docs; it changes with every endpoint.

## Deliberate non-decisions

- **No canned-response fallback.** "Graceful degradation" here means `503` + `Retry-After` + a DOWN `llm`
  health component, not a static sentence returned as if the model answered. Returning invented text would
  defeat the point of a showcase whose error bodies carry a `requestId`.
- **No real vector database.** Persistence landed as `SimpleVectorStore.save`/`load` over two files in
  `./data/rag` plus a per-filename SHA-256 manifest, precisely so the showcase stays zero-infrastructure. What it
  is *not*: a searchable-on-disk store (the RAM copy is authoritative at serve time), and not migration-safe —
  `vector-store.json` is Spring AI's format, owned by them. pgvector is the next step if the store outgrows RAM.
- **No forced `tool_choice`.** The M4-era claim in the old notes was unverifiable and was deleted; the endpoint
  keeps the empirical temperature note instead.
- **No rerank. Query-rewriting was reversed on 2026-09-30.** This bullet used to read "No rerank /
  query-rewriting advisors — on a 494M model these add latency and damage recall". Half of it was wrong, and the
  wrong half was the more interesting one. **Reranking still stands**: a second pass that reorders retrieved
  chunks costs a model call *and* can only reorder what the vector leg already fetched, so on a corpus of three
  chunks it moves nothing. **Coreference resolution is a different intervention**: it costs one call but changes
  what is retrieved at all, and §Phase 0 measured a case where the alternative is worse — a follow-up whose gold
  chunk scores 0.166 under a 0.2 floor is not "poorly ranked", it is absent, and the endpoint then returns a
  fluent answer cited to a chunk from a different section. `ARCHITECTURE_AND_METHODS.md` §2 records the bytecode
  that killed the cheaper option (attaching the memory advisor): memory is injected as separate messages, so the
  retrieval query — one string, read from the user message — never sees it. The rewrite is opt-out
  (`RAG_FOLLOW_UP_ENABLED=false`) and its cost is reported per response (`rewriteTimeMs`), because the objection
  in the old bullet is real and belongs in the response, not in a footnote.
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

1. **Doc commits stack; push state is checkable, not documented.** `e1b9ade` (upgrade) → `2b35ca4` (recorded
   that SHA) → `cf28b41` (beginner on-ramp + README pointer) → the commit that records `cf28b41`.
   `git log --oneline origin/main..HEAD` lists whatever is still local; `git status` should be clean. `data/`
   and the root `run*.log` / `ollama.log` / `pull.log` scratch files are gitignored, so they stay out of
   commits — deleting them is safe but unnecessary.
2. **No `LICENSE` file and no `<licenses>` in `pom.xml`.** The repo is on GitHub; a newcomer cannot tell what
   they may copy. Needs an author decision, not a code change.
3. **No Maven wrapper** (`mvnw`, `mvnw.cmd`, `.mvn/` are absent, though `.gitignore` already has a
   `maven-wrapper.jar` exception). `mvn` must be installed globally. `mvn wrapper:wrapper` would add it — that
   touches the build, so it needs an explicit ask.
4. **Partly closed by the eval harness, still open at the advisor level.** `GoldenQuestionsTest` exercises real
   filter expressions and real thresholds against a real `SimpleVectorStore`, so `filename` filtering and the
   floor now have offline coverage. What it does not cover is `QuestionAnswerAdvisor`'s own
   `FILTER_EXPRESSION` plumbing — that path is still only seen by a live model call.
5. Optional: `src/main/resources/docs/` holds one policy file; a second fixture would make filtered-RAG
   examples clearer without asking users to upload first. **Deliberately still open:** the eval's second document
   lives at `src/test/resources/rag/corpus/ops-runbook.md` instead, because shipping a second file would
   invalidate every quoted `Indexed 3 chunks` line and every score sample in `README.md` §6 and
   `docs/GETTING_STARTED.md` — those numbers were measured, not invented, and re-measuring them is a separate
   pass.
6. **Superseded in part on 2026-10-01: hybrid retrieval (a) shipped as `HybridVectorStore` / `KeywordIndex`,
   and groundedness became the tier-3 `GroundednessJudgeTest`.** The measurements below are kept because they
   are *why* hybrid is framed as recall insurance (a second way to be right) rather than a rank fix — read them
   before changing the fusion. (a) *Hybrid retrieval is justified by acronym recall on the vector leg*: with live `all-minilm`, all
   13 `match` rows — `pto` and `PII` included — came back at **rank 1** (MRR 1.000, same as the stub tier). The
   rank evidence on this corpus says BM25 would be a second way to be right, not a fix. Re-measure unfiltered, on
   a bigger corpus, before writing `Bm25Scorer`. (b) *A grounding gate can be a score comparison*: the `nomatch`
   query "what is the espresso machine budget" scored **0.405** against `company-policy.md` (0.407 on the earlier
   run) — higher than three genuine matches (0.345 / 0.299 / 0.158). A cosine floor cannot separate answerable
   from unanswerable, so D's gate has to be a count/coverage test, or it will refuse questions the model could
   answer. (c) A third measurement, and the one that changes D's shape: `acronym-pii` retrieves its gold chunk at
   **rank 1 with cosine 0.158**, i.e. under `app.rag.similarity-threshold: 0.2`. Correct retrieval plus a correct
   drop. A gate that only looks at "did something clear the floor" cannot distinguish that from a genuine miss,
   so the refusal has to quote the rank-1 candidate's score whether or not it passed.
8. Consider a local model upgrade if factual answers matter more than staying offline.
9. `ARCHITECTURE_AND_METHODS.md`, `README.md` and `docs/GETTING_STARTED.md` were rewritten against 1.1.8 on
   2026-09-30 — every quoted sample in them was produced by running the command in this repo's shell that day,
   so the scores carry the drift noted in §Traps. (They were refreshed on 2026-10-01 to cover the cache /
   hybrid / agent / metrics / guardrails round, without re-measuring the 0.5B samples.) If the version moves again, the rows in
   `ARCHITECTURE_AND_METHODS.md` §8 (migration table) and the step outputs in `GETTING_STARTED.md` are the
   places that rot first.
10. **Follow-up resolution is a heuristic with a known failure class.** `PASSTHROUGH` is allowed to happen and
    four of the six outcomes cost no model call, but the acceptance rules in `RagQueryResolver` were each set by
    one observed model output — the demonstrative guard already had to be loosened once after live traffic
    discarded a correct rewrite. If the backend changes (`qwen2.5:0.5b-instruct` → anything else), re-run the
    two-turn check in §Conversation-aware RAG before trusting the numbers: a different model fails this validation
    in ways `RagQueryResolverTest` cannot predict. Also still open by choice: resolution is *not* applied to
    `GET /rag/search` (it is the debug endpoint) and RAG turns share the six-message window with `/chat/memory`
    rather than living in a separate namespace.
