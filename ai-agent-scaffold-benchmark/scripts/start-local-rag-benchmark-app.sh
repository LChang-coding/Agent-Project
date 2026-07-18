#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CODEX_FILE="$PROJECT_ROOT/codex.md"
APP_JAR="$PROJECT_ROOT/ai-agent-scaffold-app/target/ai-agent-scaffold-app.jar"

read_table_cell() {
  local pattern="$1"
  local column="$2"
  awk -F'|' -v pattern="$pattern" -v column="$column" '
    index($0, pattern) > 0 {
      value = $column
      gsub(/[`[:space:]]/, "", value)
      print value
      exit
    }
  ' "$CODEX_FILE"
}

require_value() {
  local name="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    printf 'missing local credential: %s\n' "$name" >&2
    exit 2
  fi
}

if [[ ! -r "$CODEX_FILE" || ! -r "$APP_JAR" ]]; then
  printf 'codex.md or packaged app jar is unavailable\n' >&2
  exit 2
fi

embedding_key="$(read_table_cell '| Embedding API |' 5)"
reranker_key="$(read_table_cell '| Reranker API |' 5)"
docling_key="$(read_table_cell '| Docling API |' 5)"
require_value AI_RAG_EMBEDDING_API_KEY "$embedding_key"
require_value AI_RAG_RERANKER_API_KEY "$reranker_key"
require_value AI_RAG_DOCLING_API_KEY "$docling_key"

export AI_RAG_EMBEDDING_API_KEY="$embedding_key"
export AI_RAG_RERANKER_API_KEY="$reranker_key"
export AI_RAG_DOCLING_API_KEY="$docling_key"
export SERVER_PORT="${SERVER_PORT:-8092}"
export AI_RAG_ENABLED=true
export AI_RAG_WORKER_ENABLED=true
export AI_RAG_WORKER_SCAN_BATCH_SIZE=1
export AI_RAG_KAFKA_LISTENER_ENABLED=false
export AI_RAG_OUTBOX_ENABLED=false
export AI_CONTEXT_ENABLED=false
export AI_CONTEXT_KAFKA_ENABLED=false
export AI_RAG_QDRANT_ENDPOINT="${AI_RAG_QDRANT_ENDPOINT:-http://103.205.240.84:6333}"
export AI_RAG_QDRANT_COLLECTION="${AI_RAG_QDRANT_COLLECTION:-ai_agent_rag_benchmark_v1}"
export AI_RAG_QDRANT_TIMEOUT=10s
export AI_RAG_EMBEDDING_ENDPOINT="${AI_RAG_EMBEDDING_ENDPOINT:-http://103.205.240.84:8081}"
export AI_RAG_EMBEDDING_TIMEOUT=30s
export AI_RAG_RERANKER_ENDPOINT="${AI_RAG_RERANKER_ENDPOINT:-http://103.205.240.84:8082}"
export AI_RAG_RERANKER_TIMEOUT=30s
export AI_RAG_DOCLING_ENDPOINT="${AI_RAG_DOCLING_ENDPOINT:-http://103.205.240.84:5001/v1}"
export OBJECT_STORAGE_TYPE=local
export OBJECT_STORAGE_LOCAL_ROOT="${OBJECT_STORAGE_LOCAL_ROOT:-/tmp/ai-agent-rag-benchmark/object-storage}"
export OBS_LOG_DIR="${OBS_LOG_DIR:-/tmp/ai-agent-rag-benchmark/log}"
export SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false

JAVA_BIN="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}/bin/java"
exec "$JAVA_BIN" -jar "$APP_JAR" \
  "--server.port=$SERVER_PORT" \
  --ai.rag.enabled=true \
  --ai.rag.worker.enabled=true \
  --ai.rag.worker.scan-batch-size=1 \
  --ai.rag.kafka.listener-enabled=false \
  --ai.rag.outbox.enabled=false \
  --ai.context.enabled=false \
  --ai.context.kafka.enabled=false \
  --ai.storage.type=local \
  "--ai.storage.local-root=$OBJECT_STORAGE_LOCAL_ROOT" \
  --spring.cloud.nacos.discovery.enabled=false
