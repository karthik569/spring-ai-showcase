#!/usr/bin/env bash
# ==============================================================================
# Script: start-spring-ai.sh
# Connects Spring AI to the local offline llama.cpp server
# ==============================================================================

export OPENAI_API_KEY="local-offline-key"
# In Spring AI, base-url must NOT include /v1 (it is appended automatically)
export OPENAI_BASE_URL="http://localhost:8081"
export OPENAI_MODEL="qwen2.5"
# The vectorizer runs on its own llama-server process (see start-offline-llm.sh)
export OPENAI_EMBEDDING_BASE_URL="http://localhost:8082"
export OPENAI_EMBEDDING_MODEL="all-minilm"

echo "================================================================="
echo " Starting Spring AI Application (Offline Mode)"
echo " Base URL:      $OPENAI_BASE_URL"
echo " Model:         $OPENAI_MODEL"
echo " Embedding URL: $OPENAI_EMBEDDING_BASE_URL"
echo " Server:        http://localhost:8080"
echo "================================================================="

cd /sdcard/Download/termux/spring-ai-showcase
mvn spring-boot:run
