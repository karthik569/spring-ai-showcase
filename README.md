# Spring AI Showcase 🤖

A modern, production-grade Spring AI reference application built with **Java 21**, **Spring Boot 3.3**, and **Spring AI 1.0.0-M4**.

This application demonstrates the complete suite of Spring AI capabilities including **ChatClient**, **Chat Memory**, **Retrieval-Augmented Generation (RAG)**, **Dynamic Tool / Function Calling**, **Structured Record Output Mapping**, and **Multimodal Vision** — running either **100% Offline on Termux (Android)** or connected to **Cloud LLM APIs (OpenAI / Ollama / LM Studio)**.

---

## 🏛 System Architecture

```
+---------------------------------------------------------------------------------------+
|                                    Client (cURL / Web)                                 |
+------------------------------------------+--------------------------------------------+
                                           | HTTP Requests (Port: 8080)
                                           v
+---------------------------------------------------------------------------------------+
|                                  Spring AI Application                                 |
|                                                                                       |
|   +--------------------------+   +--------------------------+   +------------------+  |
|   |     ChatController       |   | StructuredOutputControl. |   | RagController    |  |
|   |  - Simple Prompts        |   |  - MovieRecommendation   |   |  - QA Advisor    |  |
|   |  - System Prompts        |   |  - CodeReviewReport      |   |  - Sim. Search   |  |
|   |  - Streaming (SSE Flux)  |   |                          |   |                  |  |
|   |  - Chat Memory (Session) |   +--------------------------+   +--------+---------+  |
|   +------------+-------------+                                           |            |
|                |                                                         | Context    |
|                v                                                         v            |
|   +--------------------------+                                  +------------------+  |
|   |  ToolCallingController   |                                  |   VectorStore    |  |
|   |  - getCurrentWeather()   |                                  | (SimpleVector-   |  |
|   |  - getOrderStatus()      |                                  |   Store in RAM)  |  |
|   +------------+-------------+                                  +--------+---------+  |
|                |                                                         |            |
|                +---------------------------+-----------------------------+            |
|                                            |                                          |
|                                            v                                          |
|                              +---------------------------+                            |
|                              |    Spring AI ChatClient   |                            |
|                              +-------------+-------------+                            |
+--------------------------------------------|------------------------------------------+
                                             | OpenAI Protocol (/v1/chat/completions)
                                             v
                      +----------------------------------------------+
                      |         LLM Inference Engine                 |
                      |                                              |
                      |  [Option 1: 100% Offline in Termux]          |
                      |  llama-server (Port 8081)                    |
                      |  Model: Qwen2.5-0.5B-Instruct-Q4_K_M.gguf    |
                      |                                              |
                      |  [Option 2: Cloud / Remote]                  |
                      |  OpenAI / Ollama / LM Studio / Gemini API    |
                      +----------------------------------------------+
```

---

## 🌟 Spring AI Capabilities Breakdown

| Feature | Component | Implementation Details |
| :--- | :--- | :--- |
| **Fluent `ChatClient`** | [`ChatController.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/controller/ChatController.java) | Modern programmatic API with user/system roles, parameter templating, and reactive streaming (`Flux<String>`). |
| **Multi-Turn Chat Memory** | [`AiConfig.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/config/AiConfig.java) | Preserves conversational context across multiple turns using conversation IDs via `MessageChatMemoryAdvisor` and `InMemoryChatMemory`. |
| **Structured Output Extraction** | [`StructuredOutputController.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/controller/StructuredOutputController.java) | Automatically maps LLM output to strongly typed Java 21 Records ([`MovieRecommendation`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/dto/MovieRecommendation.java), [`CodeReviewReport`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/dto/CodeReviewReport.java)). |
| **Retrieval-Augmented Gen (RAG)** | [`RagController.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/controller/RagController.java) | Auto-ingestion of markdown files via [`TokenTextSplitter`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/service/RagDocumentIngestionService.java), in-memory `VectorStore` indexing, similarity search, and `QuestionAnswerAdvisor`. |
| **Tool / Function Calling** | [`ToolCallingController.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/controller/ToolCallingController.java) | LLM dynamically decides when to invoke Java backend functions ([`WeatherToolService`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/service/WeatherToolService.java), [`OrderToolService`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/service/OrderToolService.java)) to fetch live data. |
| **Multimodal / Vision** | [`MultimodalController.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/controller/MultimodalController.java) | Visual question answering via Spring AI `Media` abstraction. |

---

## 🚀 Mode 1: Run 100% Offline in Termux (No Internet Needed)

The project includes preconfigured startup scripts and a quantized GGUF model (`Qwen2.5-0.5B-Instruct-Q4_K_M.gguf`) that runs directly on your phone's ARM64 CPU with `llama.cpp`.

### Step 1: Start the Local LLM Server (Port 8081)
In your first Termux terminal:
```bash
cd /sdcard/Download/termux/spring-ai-showcase
bash start-offline-llm.sh
```
*Output confirmation: `main: server is listening on http://127.0.0.1:8081`*

---

### Step 2: Start Spring AI Application (Port 8080)
In your second Termux terminal:
```bash
cd /sdcard/Download/termux/spring-ai-showcase
bash start-spring-ai.sh
```
*Spring Boot starts on port 8080 connected to the local offline LLM.*

---

## 🌐 Mode 2: Run with Cloud APIs (OpenAI / Ollama / LM Studio)

If you prefer to connect to OpenAI or a remote LLM server:

```bash
cd /sdcard/Download/termux/spring-ai-showcase

# Option A: OpenAI Cloud
export OPENAI_API_KEY="sk-..."
export OPENAI_BASE_URL="https://api.openai.com"
export OPENAI_MODEL="gpt-4o-mini"
mvn spring-boot:run

# Option B: Local Ollama on LAN/Desktop
export OPENAI_API_KEY="ollama"
export OPENAI_BASE_URL="http://192.168.1.100:11434"
export OPENAI_MODEL="llama3.1"
mvn spring-boot:run
```

---

## 🧪 Interactive API Documentation & cURL Examples

All requests are sent to the Spring AI application on port `8080`.

### 1. Basic Chat Completion
- **Endpoint**: `GET /api/ai/chat`
- **Parameter**: `message` (String)

```bash
curl -i "http://localhost:8080/api/ai/chat?message=Explain+Spring+AI+in+one+sentence"
```
**Sample Response:**
```json
{
  "prompt": "Explain Spring AI in one sentence",
  "response": "Spring AI provides an abstraction layer and fluent API to seamlessly integrate modern AI models, vector databases, and tools into Spring Boot applications."
}
```

---

### 2. Prompt Templating with Dynamic Parameters
- **Endpoint**: `GET /api/ai/chat/template`
- **Parameters**: `role` (String), `topic` (String)

```bash
curl -i "http://localhost:8080/api/ai/chat/template?role=Junior+Developer&topic=Database+Indexing"
```
**Sample Response:**
```json
{
  "role": "Junior Developer",
  "topic": "Database Indexing",
  "explanation": "Think of a database index like the index at the back of a textbook: instead of reading every page to find a topic, you look up the keyword and jump directly to the right page number!"
}
```

---

### 3. Real-Time Token Streaming (Server-Sent Events)
- **Endpoint**: `GET /api/ai/chat/stream`
- **Parameter**: `message` (String)

```bash
curl -N "http://localhost:8080/api/ai/chat/stream?message=Write+a+poem+about+Java+21"
```
*Stream chunks are sent token-by-token in real-time as SSE data events.*

---

### 4. Multi-Turn Conversational Memory
- **Endpoint**: `GET /api/ai/chat/memory`
- **Parameters**: `conversationId` (String), `message` (String)

```bash
# Turn 1: Teach name & background
curl "http://localhost:8080/api/ai/chat/memory?conversationId=session-42&message=My+name+is+Alice+and+I+am+a+Java+architect"

# Turn 2: Query memory
curl "http://localhost:8080/api/ai/chat/memory?conversationId=session-42&message=What+is+my+name+and+profession?"
```
**Sample Response (Turn 2):**
```json
{
  "conversationId": "session-42",
  "message": "What is my name and profession?",
  "response": "Your name is Alice, and you are a Java architect."
}
```

---

### 5. Structured Output (Typed Java Records)

#### Movie Recommendation (`MovieRecommendation.java`):
- **Endpoint**: `GET /api/ai/structured/movie`
- **Parameter**: `genre` (String)

```bash
curl "http://localhost:8080/api/ai/structured/movie?genre=Cyberpunk"
```
**Sample Response:**
```json
{
  "title": "Blade Runner 2049",
  "releaseYear": 2017,
  "director": "Denis Villeneuve",
  "genre": "Cyberpunk",
  "imdbRating": 8.0,
  "summary": "A young blade runner unearths a long-buried secret that has the potential to plunge what's left of society into chaos.",
  "keyActors": ["Ryan Gosling", "Harrison Ford", "Ana de Armas"]
}
```

#### Automated Code Review (`CodeReviewReport.java`):
- **Endpoint**: `POST /api/ai/structured/code-review`
- **Body**: Plain text source code snippet

```bash
curl -X POST http://localhost:8080/api/ai/structured/code-review \
  -H "Content-Type: text/plain" \
  -d 'public User find(String id) { return db.query("SELECT * FROM users WHERE id=" + id); }'
```
**Sample Response:**
```json
{
  "language": "Java",
  "qualityScoreOutOf100": 25,
  "detectedVulnerabilities": [
    "SQL Injection vulnerability caused by string concatenation in raw SQL query"
  ],
  "performanceTips": [
    "Use prepared statements with parameterized inputs"
  ],
  "suggestedRefactoring": "public User find(String id) { return db.query(\"SELECT * FROM users WHERE id=?\", id); }",
  "overallVerdict": "FAIL - Critical Security Vulnerability"
}
```

---

### 6. Retrieval-Augmented Generation (RAG)
- **Endpoint**: `POST /api/ai/rag/query`
- **Body**: `{"question": "...", "topK": 3}`

```bash
curl -X POST http://localhost:8080/api/ai/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question": "How much home office equipment stipend do employees get, and what is the PTO policy?"}'
```
**Sample Response:**
```json
{
  "question": "How much home office equipment stipend do employees get, and what is the PTO policy?",
  "answer": "According to the company policy, employees are entitled to a $1,500 annual home office equipment stipend. The leave policy includes 25 days of standard annual leave plus 10 company holidays, 16 weeks of fully paid parental leave, and 4 wellness days per year.",
  "sourceDocuments": [
    "company-policy.md: # Enterprise AI & Remote Work Policies 2026\n\n## 1. Remote Work & Equipment\nEmployees are entitled to a $1,500 annual home..."
  ],
  "responseTimeMs": 750
}
```

#### Raw Vector Similarity Search:
- **Endpoint**: `GET /api/ai/rag/search`

```bash
curl "http://localhost:8080/api/ai/rag/search?query=conference+training+budget&topK=2"
```

---

### 7. Dynamic Tool / Function Calling
The LLM inspects the prompt, determines whether live tools need to be called, executes the Java `@Bean` function, and integrates the live output into the natural language response.

#### Live Weather Tool Call (`WeatherToolService`):
- **Endpoint**: `GET /api/ai/tools/weather`

```bash
curl "http://localhost:8080/api/ai/tools/weather?prompt=What+is+the+weather+in+Tokyo+right+now?"
```

#### Order Tracking Tool Call (`OrderToolService`):
- **Endpoint**: `GET /api/ai/tools/order`

```bash
curl "http://localhost:8080/api/ai/tools/order?prompt=What+is+the+delivery+status+of+order+ORD-101?"
```

#### Combined Multi-Tool Call:
- **Endpoint**: `GET /api/ai/tools/multi`

```bash
curl "http://localhost:8080/api/ai/tools/multi?prompt=Check+the+weather+in+London+and+the+shipping+status+of+order+ORD-103"
```

---

### 8. Multimodal Vision Analysis
- **Endpoint**: `GET /api/ai/vision/describe`
- **Parameters**: `imageUrl` (String), `question` (String)

```bash
curl "http://localhost:8080/api/ai/vision/describe?imageUrl=https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/280px-PNG_transparency_demonstration_1.png&question=What+shapes+and+colors+are+in+this+picture?"
```

---

## 🛠 Project Build & Packaging Commands

```bash
cd /sdcard/Download/termux/spring-ai-showcase

# Clean compile
mvn clean compile

# Package executable JAR
mvn clean package -DskipTests

# Run generated JAR directly
java -jar target/spring-ai-showcase-1.0.0-SNAPSHOT.jar
```
