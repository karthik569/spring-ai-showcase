#!/usr/bin/env bash
# ==============================================================================
# Script: start-offline-llm.sh
# Starts the two local llama.cpp OpenAI-compatible servers:
#   - chat on 8081 (Qwen2.5-1.5B-Instruct)
#   - embeddings on 8082 (all-MiniLM-L6-v2)
# llama-server loads one model per process, so the vectorizer needs its own port;
# the app points at it through app.embedding.base-url (OPENAI_EMBEDDING_BASE_URL).
# ==============================================================================

CHAT_MODEL="/sdcard/Download/termux/models/qwen2.5-1.5b-instruct-q4_k_m.gguf"
CHAT_PORT=8081
EMBED_MODEL="/sdcard/Download/termux/models/all-MiniLM-L6-v2-Q8_0.gguf"
EMBED_PORT=8082
THREADS=4
CTX_SIZE=4096
EMBED_CTX=512

mkdir -p /sdcard/Download/termux/models

if [ ! -f "$CHAT_MODEL" ]; then
    echo "[ERROR] Chat model not found at: $CHAT_MODEL"
    echo "Downloading Qwen2.5-1.5B-Instruct..."
    curl -L -o "$CHAT_MODEL" "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
fi

if [ ! -f "$EMBED_MODEL" ]; then
    echo "[ERROR] Embedding model not found at: $EMBED_MODEL"
    echo "Downloading all-MiniLM-L6-v2..."
    curl -L -o "$EMBED_MODEL" "https://huggingface.co/second-state/All-MiniLM-L6-v2-Embedding-GGUF/resolve/main/all-MiniLM-L6-v2-Q8_0.gguf"
fi

echo "================================================================="
echo " Starting embedding server on port $EMBED_PORT (background)"
echo " Model: $EMBED_MODEL"
echo "================================================================="
llama-server \
    -m "$EMBED_MODEL" \
    --host 127.0.0.1 \
    --port "$EMBED_PORT" \
    -t "$THREADS" \
    -c "$EMBED_CTX" \
    --embedding \
    --pooling mean \
    > /tmp/embed-server.log 2>&1 &
EMBED_PID=$!
echo " embedding server pid $EMBED_PID (log: /tmp/embed-server.log)"

echo "================================================================="
echo " Starting chat server on port $CHAT_PORT (foreground)"
echo " Model: $CHAT_MODEL"
echo " Threads: $THREADS | Context Window: $CTX_SIZE tokens"
echo "================================================================="

llama-server \
    -m "$CHAT_MODEL" \
    --host 127.0.0.1 \
    --port "$CHAT_PORT" \
    -t "$THREADS" \
    -c "$CTX_SIZE" \
    --embedding \
    --pooling mean
