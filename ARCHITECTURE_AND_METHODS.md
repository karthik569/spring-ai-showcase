# Spring AI & Local LLM - In-Depth Architecture & Method Guide 🧠

This document provides a comprehensive, method-by-method technical deep dive into `spring-ai-showcase`. It details the offline local `llama.cpp` server integration, VectorStore RAG document indexing, dynamic tool/function calling, and structured JSON output.

---

## 1. Project Overview & AI Architecture

The application runs on port `8080` (or `8096`) using **Spring AI 1.0.0-M4** connected to a 100% offline local `llama.cpp` inference engine running `Qwen2.5-0.5B-Instruct-Q4_K_M.gguf`. It demonstrates:
1. **Offline Local Inference**: Zero internet connection or API keys required; runs directly on device hardware.
2. **VectorStore Retrieval-Augmented Generation (RAG)**: In-memory vector embedding similarity search with mean pooling (`--pooling mean`).
3. **Dynamic Tool / Function Calling**: Model pauses text generation, triggers Java business functions (`OrderToolService`, `WeatherToolService`), and synthesizes final responses.
4. **Structured JSON Output**: Binding unstructured model responses into strongly-typed Java records.

```
 [User Prompt: "What is the status of order ORD-101?"]
                         │
                         ▼
             [SpringAiChatController]
                         │
                         ▼
              [Spring AI ChatClient]
                         │
                         ▼
             [Local llama-server (Port 8080)]
                         │
                         │ 1. Emits Function Call: getOrderStatus({"orderId":"ORD-101"})
                         ▼
             [OrderToolService.getOrderStatus()]
                         │
                         │ 2. Returns JSON: { status: "SHIPPED", address: "123 Market St" }
                         ▼
             [Local llama-server]
                         │
                         │ 3. Generates Natural Language Summary
                         ▼
 [Response: "Order ORD-101 is currently SHIPPED to 123 Market St..."]
```

---

## 2. In-Depth Class & Method Breakdown

### A. AI Tool & Function Calling Layer

#### [`OrderToolService.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/service/OrderToolService.java)
- **`Function<Request, OrderDetails> getOrderStatus()`**:
  - *Annotation*: `@Bean` + `@Description("Look up the shipping and processing status of a customer order by its Order ID")`.
  - *Operation*: Exposes function schema to the model. When the LLM decides to call this tool, Spring AI binds the model's generated JSON argument into `Request(orderId)`, looks up the order in `orderDatabase`, and returns `OrderDetails(orderId, customer, status, address, deliveryDate, total)`.

#### [`WeatherToolService.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/service/WeatherToolService.java)
- **`Function<Request, WeatherInfo> getCurrentWeather()`**:
  - *Annotation*: `@Bean` + `@Description("Get the current live weather conditions and temperature for a given city")`.
  - *Operation*: Exposes city weather lookup to the LLM. Returns temperature, conditions, humidity, and wind speed.

---

### B. VectorStore & RAG Layer

#### [`RagDocumentIngestionService.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/service/RagDocumentIngestionService.java)
- **`void init()`**:
  - *Annotation*: `@PostConstruct`.
  - *Operation*: Ingests domain knowledge documents (product return policies, technical manuals), calculates vector embeddings via the local embedding model, and indexes them in `SimpleVectorStore`.
- **`List<Document> searchSimilarDocuments(String query, int topK)`**:
  - *Operation*: Executes cosine similarity search on the vector embeddings and retrieves the top-$K$ most relevant context documents.

---

### C. AI Chat Controller Layer

#### [`SpringAiChatController.java`](file:///sdcard/Download/termux/spring-ai-showcase/src/main/java/com/example/springai/controller/SpringAiChatController.java)
- **`String chat(String prompt)`**: `GET /api/ai/chat`; direct prompt generation.
- **`String chatWithRag(String question)`**: `GET /api/ai/rag`; queries VectorStore for relevant context and injects them into the prompt template before calling the LLM.
- **`String chatWithTools(String prompt)`**: `GET /api/ai/tools`; binds `orderToolService` and `weatherToolService` function beans, enabling autonomous tool calling.
- **`MovieRecommendation structuredOutput(String actor)`**: `GET /api/ai/structured`; uses Spring AI's `BeanOutputConverter` to force the LLM to output valid JSON conforming strictly to the `MovieRecommendation` Java record.
