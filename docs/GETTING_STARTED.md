# Getting started with spring-ai-showcase

For someone who has **never used Spring AI**. Roughly 20 minutes to a running app and a working intuition for
what each capability is.

Four documents, four jobs:

| Document | For whom |
| :--- | :--- |
| **this file** | first time — install it, run it, understand what you are looking at |
| [`README.md`](../README.md) | reference — every endpoint, every config key, every error code |
| [`ARCHITECTURE_AND_METHODS.md`](../ARCHITECTURE_AND_METHODS.md) | the *why* — class by class, including the traps |
| [`docs/SESSION-HANDOFF.md`](SESSION-HANDOFF.md) | maintainer notes — machine-specific, not a tutorial |

---

## 1. What you need installed

| Requirement | Notes |
| :--- | :--- |
| **JDK 21 or newer** | `pom.xml` sets `<java.version>21</java.version>` and the compiler plugin takes `source`/`target` from it, so an older JDK stops the build at the compiler (`release version 21 not supported`). This repo was last built on JDK 25. |
| **Maven** | 3.6+ is fine. **There is no Maven wrapper in this repo** — no `mvnw`, no `mvnw.cmd`, no `.mvn/` — so `mvn` must be on your `PATH`. |
| **A model backend** | Either [Ollama](https://ollama.com) (free, offline, no key) **or** a hosted OpenAI-compatible API key. Everything below assumes Ollama. |
| **Port 8080 free** | `server.port: 8080`. Ollama uses 11434, so they do not collide. |

```bash
ollama --version                      # installed?
ollama pull qwen2.5:0.5b-instruct     # the chat model   (~400 MB)
ollama pull all-minilm                # the embedder     (~50 MB) — RAG needs this second model
ollama serve                          # in its own terminal; leave it running
```

Two models, not one, is not a quirk of this repo: text generation and embedding are different jobs, and the
vector search in §Step 3 is only as good as the embedder. (You *can* point `OPENAI_EMBEDDING_MODEL` at a chat
model; the scores then become meaningless noise, which is a bad way to learn RAG.)

> On Termux there is no Ollama: `start-offline-llm.sh` starts the two models itself — Qwen2.5-1.5B on 8081 and a
> dedicated all-MiniLM embedder on 8082 — and `start-spring-ai.sh` exports the matching `OPENAI_*` variables.
> Everything below still applies; only the endpoints differ (8081/8082 instead of 11434).

```bash
export OPENAI_API_KEY="ollama"                   # any non-empty string; Ollama ignores the value
export OPENAI_BASE_URL="http://localhost:11434"  # REQUIRED for Ollama — the built-in default is 8081, and no /v1 (the client appends it)
export OPENAI_MODEL="qwen2.5:0.5b-instruct"      # the default is plain `qwen2.5`; this repo pulls the -instruct tag
export OPENAI_EMBEDDING_MODEL="all-minilm"

mvn -B spring-boot:run
```

Each of those is `${VAR:default}` in
[`application.yml`](../src/main/resources/application.yml), so you can also pass them as
`-Dspring-boot.run.arguments=--spring.ai.openai.base-url=...` or set them once per shell. Nothing in the repo
stores a key: the yml only ever reads the variable.

Success looks like (the seconds vary per boot, and the ingestion line lands *after* `Started …` because a
`CommandLineRunner` runs once the context is up):

```
Tomcat started on port 8080 (http) with context path '/'
Started SpringAiApplication in 3.222 seconds (process running for 3.551)
[RAG-INGESTION] Indexed 3 chunks from company-policy.md
```

Then check `http://localhost:8080/actuator/health` — you want `"status":"UP"` and a `llm` component whose
details read `{"model":"qwen2.5:0.5b-instruct","reply":"Ok","latencyMs":422,"probedAt":"…"}`. That component is
a **real completion** behind a 30-second cache (`app.health.llm.cache-ttl`), which is why the probe is not free
and why health can lag reality by up to half a minute. If it says DOWN, the app is fine and Ollama is not: read
the `error` field, start the model server, and check again after 30 seconds. If the
`Indexed 3 chunks` line never appears and you see `Document ingestion deferred or offline` instead, the app
still started — with an **empty** knowledge base — so `/rag/*` will find nothing until Ollama is up and the app
is restarted.

Open **`http://localhost:8080/`**. That is the demo page: one panel per capability, and every result shows the
HTTP status and a `requestId`. Use it while you read the steps below — a curl command and a browser panel are
the same request, and the panel shows you the raw body.

---

## 2. Six words you need before the code makes sense

| Word | In plain language |
| :--- | :--- |
| **token** | A piece of a word — the unit a model charges time and memory in. A prompt of 150 tokens costs more than one of 15. |
| **prompt / system message** | What you ask (`user`) versus the standing instructions about how to answer (`system`). Spring AI's `ChatClient` lets you set both. |
| **embedding** | A list of numbers that represents a paragraph's *meaning*. Two paragraphs about the same thing get lists that point in nearly the same direction. |
| **cosine similarity / score** | How close two of those lists point: 1.0 identical meaning, 0 unrelated. Every retrieved chunk in this app reports its score. |
| **vector store / chunk / topK** | Split documents into **chunks**, store each chunk's embedding in a **vector store**, ask a question, and take the **topK** closest chunks. That is retrieval. |
| **advisor** | Spring AI's middleware for a model call. An **advisor** runs *before* the request reaches the model (add context: chat history, retrieved documents) and *after* (read the result: log token counts). Exactly a servlet filter, but for `ChatClient`. |

And three acronyms: **RAG** = retrieve chunks, then have the model answer using them. **SSE** = Server-Sent
Events, the HTTP streaming mechanism, so tokens appear as they are generated. **Tool calling** = the model
asks your Java method for data it does not have, and gets a second chance to answer with it.

---

## 3. A guided tour

Do these in order. Each step is one request, what comes back, and what it teaches. The quoted outputs are
verbatim from `qwen2.5:0.5b-instruct`.

### Step 1 — one prompt, one answer, and how to see inside it

```bash
curl "http://localhost:8080/api/ai/chat?message=What+does+Spring+AI+do?"
```

Teaches: `ChatClient` in its simplest form — `chatClient.prompt().user(message).call().content()`.

Now look at the plumbing that the response body does not show:

```bash
grep TOKEN_USAGE logs/app.log | tail -1     # tokens prompt=447 completion=62 total=509
tail -1 logs/endpoints.log                  # method path -> status latency ip= req=<body> resp=<body>
```

Both files live under `logs/` (gitignored). `TOKEN_USAGE` is its own logger, so it is one `grep` away, and the
id in square brackets at the start of every line **is** the `requestId`: every AI call in this app is logged
with it, so one grep gives you the whole story of one request. Keep that habit — Step 4 uses it for something
that matters.
Code: [`ChatController.simpleChat`](../src/main/java/com/example/springai/controller/ChatController.java).

### Step 2 — memory: the model is stateless until something gives it history

Teaching pair, run exactly as written (pick any fresh `conversationId`; the count below assumes two turns):

```bash
curl "http://localhost:8080/api/ai/chat?message=Remember+that+my+cat+is+called+Miso.+Reply+ok."
curl "http://localhost:8080/api/ai/chat?message=What+is+my+cat+called?+One+word."
# → "Your cat's name is usually their first or second name. For example: - My cat's name is Fido.
#    - Another cat's name is Luna. …"        ← it invents names because it has no idea

curl "http://localhost:8080/api/ai/chat/memory?conversationId=guide-1&message=Remember+that+my+cat+is+called+Miso.+Reply+ok."
curl "http://localhost:8080/api/ai/chat/memory?conversationId=guide-1&message=What+is+my+cat+called?+One+word."
# → "Miso."     (re-run it and you may get "Misto." — same retrieval, sloppy 0.5B spelling)
```

Every response in this app is a JSON object that names its own inputs — `{prompt, response}` here,
`{message, response, conversationId}` on the memory endpoint — so you always know which of your parameters the
server actually read.

Same model, same question. The first pair forgot because each call is a fresh request; the second pair
remembered because `MessageChatMemoryAdvisor` put the earlier turns back into the prompt before sending it.

```bash
curl "http://localhost:8080/api/ai/chat/memory/history?conversationId=guide-1"
# → messageCount 4, alternating user/assistant — this is literally what gets injected next time
```

Two things to notice. Only the last **6** messages are ever injected — a window, not a transcript — so a 200-turn
conversation does not silently grow your prompt. And the rows are in an H2 file (`./data/chat-memory`), so
**restart the app and `/history?conversationId=guide-1` still answers**. Kill the JVM, `mvn -B spring-boot:run`
again, ask for the cat: it still knows. That is the difference between a `Map` in a bean and persistence.
Code: [`AiConfig`](../src/main/java/com/example/springai/config/AiConfig.java) (`chatMemory`,
`conversationalChatClient`) and `ChatController`.

### Step 3 — RAG: retrieval, and the moment retrieval silently fails

```bash
curl "http://localhost:8080/api/ai/rag/search?query=pto&topK=3"
```

You get `{query, totalResults, results[]}`, and each result is `{id, score, content, metadata}` — the chunk
text, its cosine score, and `metadata.filename` / `origin` / `ingestedAt` telling you where it came from (Spring
also fills in `distance`, which is simply `1 - score`, plus its own `source` key that duplicates the filename).
No model call answers anything — this is the raw search, and it is the endpoint you debug with. Two things fall
out of it immediately:

- the top chunk scored **≈0.33** and the second **≈0.20**, so a three-letter acronym is *weak* retrieval;
- ask in a sentence and the same chunk climbs:
  `…/search?query=How+much+home+office+equipment+stipend+do+employees+get` → **≈0.56**.

Embeddings compare *meanings*, not keywords, and they need enough words to have something to compare. (Scores
also wobble by a few points between runs — the local embedder is not bit-stable — so treat `≈` literally.)

Now the grounded answer, with citations:

```bash
curl -X POST http://localhost:8080/api/ai/rag/query -H "Content-Type: application/json" \
  -d '{"question":"How much home office equipment stipend do employees get?"}'
# → sourceDocuments[0] = company-policy.md, score ≈0.59, excerpt "…$1,500 annual home…"
#   answer: "Employees are entitled to a $1,500 annual home office equipment stipend."
```

…or, on the next run, the same request with the same citation answers *"You haven't mentioned any specific
instance of a home office equipment stipend in your context"*. That is the 0.5B model, not the retrieval:
`sourceDocuments` proves the chunk reached the prompt. **Read the citations to judge retrieval; read the prose
to judge the model.** Conflating the two is how people spend a day debugging a RAG pipeline that works.

Then the lesson that matters more than the happy path. Upload a document that the shipped corpus does not
contain, and ask about it:

```bash
printf '# Ops Runbook\n\nDatabase failover is approved for 02:00-04:00 UTC only.\n' > target/ops-runbook.md
curl -X POST http://localhost:8080/api/ai/rag/documents -F "file=@target/ops-runbook.md"
# → {"filename":"ops-runbook.md","chunks":1}
```

(`target/` is gitignored, so the scratch file never dirties the repo root. The response is a `Map.of(...)`,
which makes **no** promise about key order — read it by key, never by position. The number that matters is
`chunks: 1`, i.e. the text was split and embedded.)

```bash
curl "http://localhost:8080/api/ai/rag/search?query=when+is+failover+allowed%3F&topK=3&filename=ops-runbook.md"
# → one result, score 0.1118, filename ops-runbook.md   ← the chunk IS there

curl -X POST http://localhost:8080/api/ai/rag/query -H "Content-Type: application/json" \
  -d '{"question":"when is failover allowed?","filename":"ops-runbook.md"}'
# → answer: "Sorry, but I don't have enough context or history data to determine when failover is allowed…"
#   sourceDocuments: []
```

0.11 is below `app.rag.similarity-threshold`, which is **0.2**, so the advisor retrieved nothing and the model
answered from memory — fluently, and with no idea what you asked. That is what "RAG is silently not grounded"
looks like, and it is why `/rag/search` exists separately from `/rag/query`, why `/rag/query` returns the
chunks it actually used, and why the threshold in this repo is 0.2 rather than the 0.5 you might expect.

Run the experiment, because it is the fastest way to own this intuition. `{"question":"pto"}` at the shipped
threshold returns exactly one citation at ≈0.33. Restart the app with a higher floor and the same question
loses its context entirely:

```bash
mvn -B spring-boot:run -Dspring-boot.run.arguments=--app.rag.similarity-threshold=0.4
# then: /rag/query {"question":"pto"} → sourceDocuments: []   (was one chunk at 0.3285)
```

Same question, same chunks, same model — the only difference is a number in a config file, and the only
evidence is the citation list.

There is a second way to land in that same empty-handed state, and it needs no config change at all: ask
something on a topic, then ask a follow-up that refers back to it.

```bash
curl -X POST http://localhost:8080/api/ai/rag/query -H "Content-Type: application/json" \
  -d '{"question":"What is the home office equipment stipend?","conversationId":"step3"}'
curl -X POST http://localhost:8080/api/ai/rag/query -H "Content-Type: application/json" \
  -d '{"question":"how long do I have to submit that?","conversationId":"step3"}'
# → resolvedQuestion: "How long do you have to submit the home office equipment stipend?"
#   followUpOutcome: REWRITTEN, rewriteTimeMs: 325
#   sourceDocuments[0] = the chunk that contains "within 30 days of purchase", score 0.47
```

Search the pronoun instead and you get a different picture, which `/rag/search` shows without a model call:

```bash
curl "http://localhost:8080/api/ai/rag/search?query=how+long+do+I+have+to+submit+that%3F&topK=1&filename=company-policy.md"
# → one result, score 0.2185, and that chunk does NOT contain the 30-day rule   ← plausible, wrong
```

That is the same failure as the runbook above — a chunk that clears the floor and cannot answer — only now the
reason is an unresolved *that*, and the citation list alone would not tell you: it looks like a real match. So
`/rag/query` reports `resolvedQuestion` on every response, including the ones where nothing was rewritten
(`followUpOutcome` says why: `NO_CONVERSATION`, `EMPTY_MEMORY`, `PASSTHROUGH`, `DISABLED`, …), and the demo page
prints it above the answer. Two things this deliberately does **not** do:

- it is not an advisor. `QuestionAnswerAdvisor` builds its search from the user message text and then replaces
  that text, while `MessageChatMemoryAdvisor` injects history as *separate* messages — so memory is in the prompt
  before retrieval runs and is still invisible to the retrieval query. Attaching the memory advisor to the RAG
  client changes nothing here; the query string itself has to become standalone first. Read
  [`ARCHITECTURE_AND_METHODS.md`](../ARCHITECTURE_AND_METHODS.md) §2 for the bytecode-level note, and treat this as
  the repo's second lesson in *"the advisor you assumed was doing it wasn't"*.
- it does not fix the model. On the run above the retrieval handed the 0.5B model the right chunk at 0.47 and the
  answer still came back "the context does not mention…". Citations are the evidence; the prose is the ceiling.

The cost is one extra serial model call — 325–595 ms and ~126–148 tokens here, against a 1.1–2.1 s baseline. Set
`RAG_FOLLOW_UP_ENABLED=false` and the endpoint is the single-shot one it was before: `followUpOutcome` becomes
`DISABLED`, `resolvedQuestion` echoes your text, no rewrite call, and no RAG turn written to chat memory. Note
too that these turns share the six-message window with `/chat/memory`, so a RAG exchange displaces two chat
messages there.

There is a version of that experiment you can run with the model switched off. `mvn -B test` indexes the same
policy document into the same store, embeds it with a deterministic term-hash stub, and checks 15 golden
questions by **rank**: does the chunk that literally contains *"$1,500 annual home office equipment stipend"*
come back first? It does, for all 13 answerable rows, and the stub tier asserts exactly that — so if a change
reorders retrieval you find out in 6 seconds with Ollama off. The same offline run asserts the follow-up table
too ([`FollowUpRetrievalTest`](../src/test/java/com/example/springai/rag/FollowUpRetrievalTest.java)), and that
one *does* apply the 0.2 floor, because a follow-up's failure mode is its gold chunk scoring 0.07 on the stub and
being dropped outright — no threshold-free search can see it. On the stub: unresolved retrieves nothing,
resolved retrieves the answer at 0.248. `mvn -B test -Dapp.rag.eval=true` re-runs both datasets against the live
`all-minilm` and prints the tables without asserting on them, because real scores drift — live, two of the three
follow-ups already rank 1 unresolved, which is why the win is stated as floor and honesty rather than rank. Run
it with the same `OPENAI_*` environment as the app: without a reachable embedder the store indexes nothing and
the class reports *skipped* rather than failed. On the run that produced the numbers above, every match ranked
first — and the top score for a question the corpus **cannot** answer (0.405) beat three of the genuine matches.
Rank and score are measuring different things here, which is the whole reason the gate is on rank.

> **Now restart the app — the upload is still there.** Every index change snapshots the store into `data/rag/`:
> `vector-store.json` is Spring AI's own serialization, `rag-store.json` is this repo's per-filename SHA-256
> manifest. Re-run the search above after the restart and you get the *same chunk id* back. The boot log
> changes shape too:
>
> ```
> [RAG-STORE] Restored snapshot from .\data\rag (2 document checksum(s))
> [RAG-INGESTION] Kept persisted chunks for company-policy.md (unchanged)
> ```
>
> — no `Indexed 3 chunks` line, because the shipped document's bytes still hash to what the manifest recorded,
> so it is not re-embedded at all. That is the whole reason a manifest sits next to the snapshot: `VectorStore`
> has no scan API, so "already indexed?" cannot be answered without calling the embedder, and the embedder is
> the thing that may be offline at boot. Set `app.rag.store.enabled=false` to get the old behaviour back:
> re-ingest every boot, uploads lost.

Code: [`RagController`](../src/main/java/com/example/springai/controller/RagController.java),
[`RagQueryResolver`](../src/main/java/com/example/springai/service/RagQueryResolver.java),
[`RagDocumentIngestionService`](../src/main/java/com/example/springai/service/RagDocumentIngestionService.java),
[`RagStorePersistence`](../src/main/java/com/example/springai/service/RagStorePersistence.java).

### Step 4 — tool calling: the model decides, and you can prove it ran

```bash
curl "http://localhost:8080/api/ai/tools/order?prompt=What+is+the+delivery+status+of+order+ORD-101?"
grep "SPRING-AI-TOOL" logs/app.log | tail -1
# → [SPRING-AI-TOOL] Executing getOrderStatus tool call for orderId=ORD-101
```

Nothing in the controller says "if the prompt mentions an order, call the order function". The tool's
`@Description` text and its parameter schema are sent with the request; the model emits a call, Spring AI
executes your Java method, feeds the result back, and the model writes the sentence.

So: **the log line is the proof.** A plausible answer *without* a `[SPRING-AI-TOOL]` line is a model that
invented the status. This is the single most important reflex to build early — with an LLM, "the answer looks
right" is not evidence. `/tools/weather` invents conditions often; `/tools/order` behaves better.
Code: [`ToolCallingController`](../src/main/java/com/example/springai/controller/ToolCallingController.java) +
[`OrderToolService`](../src/main/java/com/example/springai/service/OrderToolService.java).

### Step 5 — structure is guaranteed, truth is not

```bash
curl "http://localhost:8080/api/ai/structured/movie?genre=Cyberpunk"
# 200 → {"title":"The Matrix","releaseYear":2016,"director":"Unknown", … ,"keyActors":["Unknown"]}
# 422 → {"error":"model_output_unparseable","detail":"Model response did not contain a complete
#        MovieRecommendation object","requestId":"44dddd44",
#        "rawModelOutput":"{ \"director\": \"\", … \"releaseYear\": 2017, \"title\": \"Inception\" }"}
```

Both are real and you will get one or the other depending on the run. The 200 is a JSON object that binds
perfectly onto the `MovieRecommendation` record — and `The Matrix` is 1999, not 2016. The 422 is the same
request when the model leaves `director` empty: `validated(...)` retries once, still finds a blank field, and
refuses to pass the answer off as a recommendation. That is the contract worth copying into your own code:
**shape is enforced by the target type, completeness by your own predicate, and the model's raw text is echoed
back in `rawModelOutput` instead of a stack trace or a silent `null`.**

```bash
curl -X POST http://localhost:8080/api/ai/structured/code-review \
  -H "Content-Type: text/plain" \
  -d 'public User find(String id) { return db.query("SELECT * FROM users WHERE id=" + id); }'
# → {"language":"Java","qualityScoreOutOf100":95,"detectedVulnerabilities":[], … ,"overallVerdict":"good"}
```

A textbook SQL-concatenation injection, rated 95/100 with an empty vulnerability list — and the response is
structurally perfect. Structured output controls **shape**, never **facts**.

Same step, the failure modes worth seeing once on purpose:

```bash
curl -i "http://localhost:8080/api/ai/chat/memory?conversationId=this-id-is-way-longer-than-thirty-six-characters&message=hi"
# → 400 {"error":"request_rejected","status":400,"detail":"conversationId is limited to 36 characters","requestId":"802fe438"}
curl -N "http://localhost:8080/api/ai/chat/stream?message=Write+a+haiku+about+Maven"                    # tokens as they arrive
# now stop Ollama:
curl -i "http://localhost:8080/api/ai/chat?message=hi"        # → 503 + Retry-After: 5, body names the cause
curl "http://localhost:8080/actuator/health"                  # → status DOWN, components.llm.error "…"
```

There is a third 503 that is not an outage: the app lets **4 model calls** run at once
(`app.ai.max-concurrent-requests`) and rejects the rest immediately instead of queueing them unseen. Open the
demo page and click two panels at the same time, or fire a burst, and you will see it — its `Retry-After` is
**2** rather than the 5 the backend-down case uses, because the server made that decision, not Ollama:

```bash
for i in 1 2 3 4 5 6 7 8; do curl -s -o /dev/null -w "%{http_code} " "http://localhost:8080/api/ai/chat?message=hi$i" & done
# → 503 503 503 503 200 200 200 200
# body: {"error":"too_many_concurrent_requests","status":503,
#        "detail":"This model backend is serving 4 calls already; ask again shortly.",…}
```

Code: [`StructuredOutputController`](../src/main/java/com/example/springai/controller/StructuredOutputController.java),
[`GlobalExceptionHandler`](../src/main/java/com/example/springai/error/GlobalExceptionHandler.java).

---

## 4. Read the source in this order

1. [`SpringAiApplication.java`](../src/main/java/com/example/springai/SpringAiApplication.java) — a normal Boot main class; Spring AI is just a starter on the classpath.
2. [`application.yml`](../src/main/resources/application.yml) — the four `OPENAI_*` variables, the memory window, the RAG threshold, the concurrency ceiling. Most "what if I change this" questions are answered here first.
3. [`AiConfig.java`](../src/main/java/com/example/springai/config/AiConfig.java) — the two `ChatClient` beans and *why there are two*. Read the comments; they encode a 1.1.6 behaviour change.
4. [`ChatController.java`](../src/main/java/com/example/springai/controller/ChatController.java) — the fluent API: `.prompt().system(...).user(...).call().content()`, plus `.stream()`.
5. [`TokenUsageAdvisor.java`](../src/main/java/com/example/springai/advisor/TokenUsageAdvisor.java) — the smallest complete advisor, and its one-line ordering trap.
6. [`RagController.java`](../src/main/java/com/example/springai/controller/RagController.java) — the advisor that does retrieval, and how citations are read back from the call's own context.
7. `StructuredOutputController` → `ToolCallingController` → `MultimodalController`.
8. [`GlobalExceptionHandler.java`](../src/main/java/com/example/springai/error/GlobalExceptionHandler.java) — what production error contracts look like for a model call.
9. `src/test/java/…/ControllerIntegrationTest.java` — how to test controllers that call a model **without** calling a model.
10. [`RagStorePersistenceTest.java`](../src/test/java/com/example/springai/service/RagStorePersistenceTest.java) and [`GoldenQuestionsTest.java`](../src/test/java/com/example/springai/rag/GoldenQuestionsTest.java) — the two offline patterns behind §Step 3: a restart simulated with `@TempDir`, and retrieval asserted by rank.
11. The newer capabilities each live in their own package: `cache/`, `vectorstore/` (hybrid), `agent/`, `guardrail/`, `observability/` — read the one whose panel you are curious about.

---

## 5. Five things that will bite you

1. **`OPENAI_BASE_URL` must not end in `/v1`.** Spring AI appends `v1/chat/completions` itself; a doubled
   `/v1/v1/…` presents as a confusing 503.
2. **RAG needs a second, real embedding model.** `OPENAI_EMBEDDING_MODEL=all-minilm`. A chat model as
   embedder gives you a working pipeline with random scores.
3. **`conversationId` is 36 characters or fewer** — the memory schema column is `VARCHAR(36)`.
4. **`./data/rag/` is now your knowledge base.** Uploads survive a restart, which means the answers you get
   depend on files gitignored next to the H2 database: delete the directory and the uploads are gone, keep it
   and a stale document keeps citing. One consequence is not obvious — chunks are keyed by **filename**, so
   uploading a file named like a shipped one (`company-policy.md`) evicts the shipped chunks immediately, and
   the next boot evicts yours and re-indexes the shipped document, because its bytes no longer match the
   checksum your upload recorded. Verified: 3 shipped chunks → upload that name → 1 chunk → restart → 3 chunks,
   and the uploaded text is unrecoverable. Rename the file if you meant to add, not to shadow.
5. **One app instance at a time.** The H2 file in `./data/` is exclusively locked, so a second
   `mvn -B spring-boot:run` in another terminal dies with `Failed to determine DatabaseDriver` — which looks
   like a datasource typo and is really "something is already running on this database file". Same reason you
   stop the app before `mvn -B test`: both use `target/classes`, and Windows locks `.class` files under a live
   JVM.

Judge the plumbing, not the prose: `qwen2.5:0.5b-instruct` is 494M parameters and will invent facts inside
valid JSON. Every "wrong answer" in this document was produced on purpose to show what the *code* still gets
right — citations, provenance, token counts, and a typed error instead of a stack dump.

---

## 6. Next

The five steps above are the spine. Four more capabilities ship alongside them — each has a demo panel and a
README section, and they are deliberately *not* required to understand the core:

- **Semantic response cache** (`SemanticCacheAdvisor`) — a paraphrase of an already-answered question is
  replayed without a model call; `app.cache.*`.
- **Hybrid retrieval** (`HybridVectorStore`) — BM25 fused with the cosine ranking, so an acronym the embedder
  cannot represent is still found; `RAG_HYBRID_ENABLED`.
- **Agentic loop** (`GET /api/ai/tools/agent`) — the model chains tools across several rounds, and the response
  carries the full step trace.
- **Guardrails** — PII redaction on every answer, prompt-injection screening on uploads.

Plus a `GET /api/ai/metrics/summary` roll-up (requests, tokens, cache hit rate, latency p50/p95) that the demo
page renders as its eleventh panel. See [`README.md`](../README.md) §Capabilities.

- [`README.md`](../README.md) — full endpoint reference, config table, resilience and operations.
- [`ARCHITECTURE_AND_METHODS.md`](../ARCHITECTURE_AND_METHODS.md) — the advisor chain, ordering traps, and what changed between Spring AI milestones and 1.1.8.
- `http://localhost:8080/docs` — Swagger UI: every `/api/ai/**` operation, executable in the browser, with the
  request and response shapes generated from the code (that is why the upload panel offers a file picker).
- Want a better model? With Ollama: `ollama pull qwen2.5:3b` then `export OPENAI_MODEL=qwen2.5:3b`. With a
  hosted key: `export OPENAI_API_KEY=sk-…`, `export OPENAI_BASE_URL=https://api.openai.com`,
  `export OPENAI_MODEL=gpt-4o-mini`, `export OPENAI_EMBEDDING_MODEL=text-embedding-3-small` — **all four**, since
  the base-url default in `application.yml` points at a local server, not at OpenAI. Either way: **no code
  change is required** — that is the point of Spring AI's abstraction. README §Pointing at a hosted backend has
  the same four exports, plus why a key never reaches the log files.
