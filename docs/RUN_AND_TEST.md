# Run and test every endpoint — Windows, macOS, Linux

This is the machine-portable companion to [`GETTING_STARTED.md`](GETTING_STARTED.md). That file teaches the
concepts; this one gets the app running on **any** OS and then tests **all 21 endpoints**, stating for each one
**which model it exercises**.

The shipped `start-offline-llm.sh` / `start-spring-ai.sh` are **Termux-only** (they hard-code `/sdcard/...` and
assume `llama-server` is on the `PATH`). Do not copy them to Windows or a desktop Mac — use the commands below.

> **Verified:** the Linux → llama.cpp path in this guide was executed end-to-end on the machine that produced
> it (both servers started with the exact flags shown, the app booted, and every endpoint group was called).
> The Windows and macOS command syntaxes are given but were not executed on those OSes here — they are standard
> shell/PowerShell forms.

---

## 1. The two models — read this first

Every AI endpoint needs **one or both** of two models. They are different jobs and cannot be the same file:

| Role | What it does | Default in this repo | Endpoint property |
| :--- | :--- | :--- | :--- |
| **CHAT** (generation) | Writes the answer; also rewrites follow-ups, grades passages, summarizes memory, answers the health probe | `Qwen2.5-1.5B-Instruct` (llama.cpp) · `qwen2.5:0.5b-instruct` (Ollama) | `OPENAI_BASE_URL` + `OPENAI_MODEL` |
| **EMBED** (vectorization) | Turns text into the vectors retrieval ranks by | `all-MiniLM-L6-v2` (llama.cpp) · `all-minilm` (Ollama) | `OPENAI_EMBEDDING_BASE_URL` + `OPENAI_EMBEDDING_MODEL` |

A chat model used as the vectorizer still returns vectors, so the mistake is silent — it just ranks
near-randomly. Keep the embedder a real embedding model on its own endpoint.

`llama-server` loads one model per process, which is why the llama.cpp mode runs **two** servers on **two**
ports. Ollama multiplexes both models behind one port (11434).

### Which model each endpoint uses

Method + path | CHAT | EMBED | Notes
:--- | :---: | :---: | :---
`GET /actuator/health` | ✅ | — | One-token probe, cached 30 s (`app.health.llm.cache-ttl`)
`GET /api/ai/metrics/summary` | — | — | In-process counters only
`GET /api/ai/chat` | ✅ | ✅ | Plain question → the semantic cache embeds it even on a miss
`GET /api/ai/chat/template` | ✅ | ✅ | Same cache path
`GET /api/ai/chat/stream` | ✅ | — | Conversational client; the cache is not attached
`GET /api/ai/chat/memory` | ✅ | — | Conversational client
`GET /api/ai/chat/memory/history` | — | — | Reads the H2 transcript directly
`DELETE /api/ai/chat/memory` | — | — | Clears the H2 transcript
`GET /api/ai/structured/movie` | ✅ | — | Carries a JSON schema, so the cache skips it
`POST /api/ai/structured/code-review` | ✅ | — | Same
`GET /api/ai/tools/weather` | ✅ | — | Tool result is an in-memory fixture
`GET /api/ai/tools/order` | ✅ | — | Tool result is an in-memory fixture
`GET /api/ai/tools/multi` | ✅ | — | Two tools in one turn
`GET /api/ai/tools/calculate` | ✅ | — | Local tool only
`GET /api/ai/tools/datetime` | ✅ | — | Local tool only
`GET /api/ai/tools/assistant` | ✅ | ⚙️ | EMBED only if the model calls `searchKnowledgeBase`
`GET /api/ai/tools/assistant/stream` | ✅ | ⚙️ | Same toolbox
`GET /api/ai/tools/agent` | ✅ | ⚙️ | Same toolbox, multi-step loop
`GET /api/ai/vision/analyze` | ✅\* | — | \*CHAT must have a **vision encoder** — see §7
`POST /api/ai/rag/query` | ✅ | ✅ | EMBED retrieval, then CHAT for grader + answer
`GET /api/ai/rag/search` | — | ✅ | EMBED (+ BM25 keyword) only
`POST /api/ai/rag/documents` | — | ✅ | Indexing embeds the uploaded chunks

✅ = always · ⚙️ = only when the model chooses that tool · — = not used.

Turn the cache off (`SEMANTIC_CACHE_ENABLED=false`) if you want the two plain-chat endpoints to be
chat-model-only.

---

## 2. Prerequisites

| Requirement | Notes |
| :--- | :--- |
| **JDK 21 or newer** | `pom.xml` sets `java.version` 21. On a JDK older than 21 the build stops at the compiler. |
| **Maven 3.6+ on `PATH`** | **There is no Maven wrapper** — no `mvnw`. Install Maven or use your IDE's bundled one. |
| **A model backend** | Ollama (§3A, nothing to download) **or** llama.cpp (§3B, two GGUF files). |
| **Free ports** | 8080 = app · 8081/8082 = llama.cpp chat/embed · 11434 = Ollama. |
| **Disk** | ~1.2 GB for the two GGUF files (llama.cpp mode); ~450 MB for the Ollama models. |

```text
java -version     # must print 21 or higher
mvn -version      # must print 3.6 or higher
```

---

## 3A. Backend option A — Ollama (easiest; one port, all three OSes)

Install Ollama from <https://ollama.com/download> (Windows installer, macOS `.dmg`, Linux script), then:

```bash
ollama serve                        # leave running; on Windows/macOS the installer starts it for you
ollama pull qwen2.5:0.5b-instruct   # CHAT  (~400 MB) — Chinese/English instruct, fine for this demo
ollama pull all-minilm              # EMBED (~50 MB)
```

Ollama listens on `http://localhost:11434`. That single endpoint serves both `/v1/chat/completions` and
`/v1/embeddings`. The model tags must match what you pulled.

> Want stronger answers than the 0.5B? `ollama pull qwen2.5:1.5b-instruct` and set
> `OPENAI_MODEL=qwen2.5:1.5b-instruct`. Bigger model = slower on CPU.

---

## 3B. Backend option B — llama.cpp (two servers; what the Termux scripts do)

Install `llama-server` (from the [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases) —
`llama-*-bin-win-*.zip` on Windows, `brew install llama.cpp` on macOS, a package or build on Linux).

**Download the two models once** (pick the row for your shell):

```bash
# macOS / Linux (bash or zsh)
curl -L -o qwen2.5-1.5b-instruct-q4_k_m.gguf \
  https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
curl -L -o all-MiniLM-L6-v2-Q8_0.gguf \
  https://huggingface.co/second-state/All-MiniLM-L6-v2-Embedding-GGUF/resolve/main/all-MiniLM-L6-v2-Q8_0.gguf
```

```powershell
# Windows PowerShell
curl.exe -L -o qwen2.5-1.5b-instruct-q4_k_m.gguf `
  https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
curl.exe -L -o all-MiniLM-L6-v2-Q8_0.gguf `
  https://huggingface.co/second-state/All-MiniLM-L6-v2-Embedding-GGUF/resolve/main/all-MiniLM-L6-v2-Q8_0.gguf
```

**Start the two servers** — each in its own terminal, from the folder holding the `.gguf` files. The embed
server needs `--embedding --pooling mean`; the chat server must **not** use `--embedding`.

```bash
# macOS / Linux — terminal 1 (EMBED, port 8082)
llama-server -m all-MiniLM-L6-v2-Q8_0.gguf --host 127.0.0.1 --port 8082 -t 4 -c 512 --embedding --pooling mean

# macOS / Linux — terminal 2 (CHAT, port 8081)
llama-server -m qwen2.5-1.5b-instruct-q4_k_m.gguf --host 127.0.0.1 --port 8081 -t 4 -c 4096
```

```powershell
# Windows — terminal 1 (EMBED, port 8082)
llama-server.exe -m all-MiniLM-L6-v2-Q8_0.gguf --host 127.0.0.1 --port 8082 -t 4 -c 512 --embedding --pooling mean

# Windows — terminal 2 (CHAT, port 8081)
llama-server.exe -m qwen2.5-1.5b-instruct-q4_k_m.gguf --host 127.0.0.1 --port 8081 -t 4 -c 4096
```

`-t 4` = four threads; raise it toward your core count. `-c` is the **context window** in tokens: the chat
server's 4096 must fit the system prompt + retrieved chunks + memory summary. A `-c` that is too small
silently truncates the prompt and the answers get worse — 4096 is the shipped value and the smallest this demo
was tested with.

Confirm both are up (each prints a JSON model list, HTTP 200):

```bash
curl http://localhost:8082/v1/models
curl http://localhost:8081/v1/models
```

---

## 4. Point the app at the backend

The app reads `${VAR:default}` from `application.yml`, so only the environment needs to change — no file edit.

**Option A (Ollama)** — note `OPENAI_BASE_URL` is **11434**, and the model names carry the Ollama tags:

| Shell | Set the variables |
| :--- | :--- |
| macOS / Linux (bash, zsh) | `export OPENAI_API_KEY=ollama`<br>`export OPENAI_BASE_URL=http://localhost:11434`<br>`export OPENAI_MODEL=qwen2.5:0.5b-instruct`<br>`export OPENAI_EMBEDDING_BASE_URL=http://localhost:11434`<br>`export OPENAI_EMBEDDING_MODEL=all-minilm` |
| Windows PowerShell | `$env:OPENAI_API_KEY="ollama"`<br>`$env:OPENAI_BASE_URL="http://localhost:11434"`<br>`$env:OPENAI_MODEL="qwen2.5:0.5b-instruct"`<br>`$env:OPENAI_EMBEDDING_BASE_URL="http://localhost:11434"`<br>`$env:OPENAI_EMBEDDING_MODEL="all-minilm"` |
| Windows CMD | `set OPENAI_API_KEY=ollama`<br>`set OPENAI_BASE_URL=http://localhost:11434`<br>`set OPENAI_MODEL=qwen2.5:0.5b-instruct`<br>`set OPENAI_EMBEDDING_BASE_URL=http://localhost:11434`<br>`set OPENAI_EMBEDDING_MODEL=all-minilm` |

**Option B (llama.cpp)** — the app's built-in defaults already match the two servers, but set `OPENAI_API_KEY`
to any non-empty string (Spring AI refuses an empty key). PowerShell example:

```powershell
$env:OPENAI_API_KEY="local-offline-key"
$env:OPENAI_BASE_URL="http://localhost:8081"          # CHAT  (default)
$env:OPENAI_EMBEDDING_BASE_URL="http://localhost:8082" # EMBED (default)
$env:OPENAI_MODEL="qwen2.5"                            # llama-server ignores the name; the loaded model is used
$env:OPENAI_EMBEDDING_MODEL="all-minilm"
```

Do **not** append `/v1` to either base URL — the client appends it.

## 5. Start the app

```bash
mvn -B spring-boot:run
```

Success looks like:

```text
Tomcat started on port 8080 (http) with context path '/'
Started SpringAiApplication in 3.2 seconds
[RAG-INGESTION] Indexed 3 chunks from company-policy.md
```

The app boots even with **no** backend: RAG ingestion is wrapped in a try/catch, so a dead server logs
`Document ingestion deferred or offline` and startup continues — but then the knowledge base is empty and AI
calls answer `503`. Start the backend first for a clean run.

Confirm the CHAT model works with a real completion:

```bash
curl http://localhost:8080/actuator/health
# ... "llm":{"status":"UP","details":{"model":"...","reply":"ok","latencyMs":...}}
```

If `llm` is `DOWN`, the app is fine and the backend is not — read the `error`/`cause` field, and check again
after 30 s (the probe is cached).

---

## 6. Test every endpoint

### Recommended: the Swagger UI (one click per endpoint)

Open **<http://localhost:8080/docs>** (it redirects to `/swagger-ui/index.html`). Every endpoint is grouped by
tag, every operation has a summary, and every parameter is pre-filled with an example. Click **Try it out →
Execute** — no request writing needed. The raw document is at <http://localhost:8080/v3/api-docs>.

There is also a demo page at <http://localhost:8080/> with one panel per capability and a `requestId` on each
result.

### From the shell

Set `B=http://localhost:8080` (bash) or `$B = "http://localhost:8080"` (PowerShell), then:

| # | Endpoint | Models | Sample (bash) |
| :-- | :--- | :--- | :--- |
| 1 | `GET /api/ai/chat` | CHAT+EMBED | `curl -G "$B/api/ai/chat" --data-urlencode "message=Name one benefit of dependency injection."` |
| 2 | `GET /api/ai/chat/template` | CHAT+EMBED | `curl -G "$B/api/ai/chat/template" --data-urlencode "role=Java developer" --data-urlencode "topic=Spring AOP"` |
| 3 | `GET /api/ai/chat/stream` | CHAT | `curl -N "$B/api/ai/chat/stream?message=tell me a joke"` (SSE) |
| 4 | `GET /api/ai/chat/memory` | CHAT | `curl -G "$B/api/ai/chat/memory" --data-urlencode "message=My name is Ada." --data-urlencode "conversationId=demo"` |
| 5 | `GET /api/ai/chat/memory/history` | — | `curl -G "$B/api/ai/chat/memory/history" --data-urlencode "conversationId=demo"` |
| 6 | `DELETE /api/ai/chat/memory` | — | `curl -X DELETE -G "$B/api/ai/chat/memory" --data-urlencode "conversationId=demo"` |
| 7 | `GET /api/ai/structured/movie` | CHAT | `curl -G "$B/api/ai/structured/movie" --data-urlencode "genre=Science Fiction"` |
| 8 | `POST /api/ai/structured/code-review` | CHAT | `curl -X POST "$B/api/ai/structured/code-review" -H 'Content-Type: text/plain' --data-binary 'int x = r.nextInt();'` |
| 9 | `GET /api/ai/tools/weather` | CHAT | `curl -G "$B/api/ai/tools/weather" --data-urlencode "prompt=What is the weather in Tokyo?"` |
| 10 | `GET /api/ai/tools/order` | CHAT | `curl -G "$B/api/ai/tools/order" --data-urlencode "prompt=Status of order ORD-101?"` |
| 11 | `GET /api/ai/tools/multi` | CHAT | `curl -G "$B/api/ai/tools/multi" --data-urlencode "prompt=Weather in London and status of ORD-103."` |
| 12 | `GET /api/ai/tools/calculate` | CHAT | `curl -G "$B/api/ai/tools/calculate" --data-urlencode "prompt=What is 128 * 46 + 1024?"` |
| 13 | `GET /api/ai/tools/datetime` | CHAT | `curl -G "$B/api/ai/tools/datetime" --data-urlencode "prompt=What time is it in London?"` |
| 14 | `GET /api/ai/tools/assistant` | CHAT ⚙️EMBED | `curl -G "$B/api/ai/tools/assistant" --data-urlencode "prompt=How many days of annual leave do I get, and what is 3 times that number?"` |
| 15 | `GET /api/ai/tools/agent` | CHAT ⚙️EMBED | `curl -G "$B/api/ai/tools/agent" --data-urlencode "prompt=How many days of annual leave do I get?"` |
| 16 | `GET /api/ai/tools/assistant/stream` | CHAT ⚙️EMBED | `curl -N "$B/api/ai/tools/assistant/stream?prompt=hi&conversationId=assistant-1"` (SSE) |
| 17 | `GET /api/ai/vision/analyze` | CHAT\* | `curl -G "$B/api/ai/vision/analyze" --data-urlencode "imageUrl=https://upload.wikimedia.org/wikipedia/commons/3/3a/Cat03.jpg"` — see §7 |
| 18 | `POST /api/ai/rag/query` | CHAT+EMBED | `curl -X POST "$B/api/ai/rag/query" -H 'Content-Type: application/json' -d '{"question":"How much is the home office equipment stipend?"}'` |
| 19 | `GET /api/ai/rag/search` | EMBED | `curl -G "$B/api/ai/rag/search" --data-urlencode "query=home office stipend" --data-urlencode "topK=2"` |
| 20 | `POST /api/ai/rag/documents` | EMBED | `curl -X POST "$B/api/ai/rag/documents" -F "file=@./README.md"` |
| 21 | `GET /api/ai/metrics/summary` | — | `curl "$B/api/ai/metrics/summary"` |

Windows-PowerShell note: `curl` is an alias for `Invoke-WebRequest`. Use `curl.exe` for the real curl (present
on Windows 10+), or run the requests from Swagger UI instead. Quoting differs: use double quotes and escape the
inner `\"` — e.g.
`curl.exe -X POST "$B/api/ai/rag/query" -H "Content-Type: application/json" -d '{\"question\":\"How much is the home office equipment stipend?\"}'`.

### What a working run looks like (observed with the llama.cpp config above)

- `GET /api/ai/chat` → `{"response":"Dependency injection (DI) is a design pattern ..."}`
- `POST /api/ai/rag/query` → `groundingOutcome:"GROUNDED"`, `answer:"The home office equipment stipend is $1,500."`, with `company-policy.md` cited at cosine ≈ `0.63` in `sourceDocuments`.
- `GET /api/ai/rag/search?query=home office stipend` → the `company-policy.md` chunk at cosine ≈ `0.50`.
- `GET /api/ai/tools/calculate?prompt=What is 128 * 46 + 1024?` → `6912`.
- `GET /api/ai/structured/movie?genre=Science Fiction` → one flat JSON object (`title`, `releaseYear`, `director`, …). Other fields may be invented — structure is guaranteed, truth is not.
- `GET /api/ai/metrics/summary` → per-endpoint request counts, token totals, cache hit rate, latency percentiles.

Small models are non-deterministic: the tool endpoints may occasionally answer **without** calling a tool, and
the RAG gate may occasionally ground a borderline question. That is the model, not the wiring — re-run to see
variance.

---

## 7. Vision needs a vision-capable model

`GET /api/ai/vision/analyze` posts an image to the **chat** model. The default chat model
(`Qwen2.5-1.5B-Instruct` / `qwen2.5:0.5b-instruct`) is **text-only**, so with it the endpoint returns:

```json
{"error":"llm_backend_unavailable","status":503,
 "detail":"... image input is not supported - hint: ... you may need to provide the mmproj ..."}
```

That is expected. To actually test vision, point `OPENAI_MODEL` at a multimodal model:

- **llama.cpp** — a vision GGUF plus its `mmproj` projector, e.g.
  `llama-server -m llava-v1.5-7b-Q4_K_M.gguf --mmproj mmproj-model-f16.gguf --port 8081`.
- **Ollama** — `ollama pull llava` then `OPENAI_MODEL=llava`.
- **Hosted** — `OPENAI_MODEL=gpt-4o` with an OpenAI key.

Only the chat model changes; nothing in the code is image-model-specific.

---

## 8. Troubleshooting

| Symptom | Cause / fix |
| :--- | :--- |
| `release version 21 not supported` at build | JDK older than 21. Install JDK 21+ and re-run. |
| `mvn: command not found` | No Maven wrapper in this repo — install Maven or use your IDE's Maven. |
| App starts, `/rag/*` finds nothing, log says `Document ingestion deferred or offline` | The EMBED server was down at boot. Start it and restart the app. |
| `llm` health is `DOWN` | The CHAT server is unreachable. Check port 8081 (or 11434) and `OPENAI_BASE_URL`. |
| Every AI call returns `503` with `Retry-After` | Backend down, or more than `app.ai.max-concurrent-requests` (4) calls in flight. |
| Retrieval scores look like noise | The embedder is a chat model. Point `OPENAI_EMBEDDING_MODEL`/`OPENAI_EMBEDDING_BASE_URL` at `all-minilm`. |
| Ollama returns 404 for the model | Tag mismatch: pull `qwen2.5:0.5b-instruct` and set `OPENAI_MODEL` to that exact string. |
| Answers degrade as the conversation grows | Context too small. Raise the chat server's `-c`, or lower `app.memory.summarization.trigger-messages`. |
| Vision returns 503 (`image input is not supported`) | Expected with a text-only model — see §7. |
| Port already in use | Override with `mvn -B spring-boot:run -Dspring-boot.run.arguments=--server.port=8090`. |

### Knobs worth knowing (all `${VAR:default}`)

`SEMANTIC_CACHE_ENABLED` · `RAG_CRAG_ENABLED` · `RAG_RERANK_ENABLED` · `RAG_HYBRID_ENABLED` ·
`RAG_FOLLOW_UP_ENABLED` · `MEMORY_SUMMARIZATION_ENABLED` · `GUARDRAILS_REDACT_OUTPUT` ·
`GUARDRAILS_BLOCK_INJECTION` · `RAG_STORE_ENABLED` + `RAG_STORE_DIRECTORY`. Full descriptions are in
[`README.md`](../README.md) and [`ARCHITECTURE_AND_METHODS.md`](../ARCHITECTURE_AND_METHODS.md).

Set `RAG_STORE_ENABLED=false` and `CHAT_MEMORY_JDBC_URL=jdbc:h2:mem:throwaway` for a clean, stateless run that
writes nothing to `./data`.
