#!/usr/bin/env bash
# ==============================================================================
# Script: start-offline-llm.sh
# Starts the local llama.cpp OpenAI-compatible server on port 8081
# ==============================================================================

MODEL_PATH="/sdcard/Download/termux/models/qwen2.5-0.5b-instruct-q4_k_m.gguf"
PORT=8081
THREADS=4
CTX_SIZE=2048

if [ ! -f "$MODEL_PATH" ]; then
    echo "[ERROR] Model file not found at: $MODEL_PATH"
    echo "Downloading Qwen2.5-0.5B-Instruct..."
    mkdir -p /sdcard/Download/termux/models
    curl -L -o "$MODEL_PATH" "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
fi

echo "================================================================="
echo " Starting llama.cpp Offline LLM Server on Port $PORT"
echo " Model: $MODEL_PATH"
echo " Threads: $THREADS | Context Window: $CTX_SIZE tokens"
echo " OpenAI API Base URL: http://localhost:$PORT"
echo "================================================================="

llama-server \
    -m "$MODEL_PATH" \
    --host 127.0.0.1 \
    --port "$PORT" \
    -t "$THREADS" \
    -c "$CTX_SIZE" \
    --embedding \
    --pooling mean
