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
mysql_password="$(read_table_cell 'root` 或应用配置中的数据库用户' 4)"
local_mysql="${RAG_BENCHMARK_LOCAL_MYSQL:-false}"
require_value AI_RAG_EMBEDDING_API_KEY "$embedding_key"
require_value AI_RAG_RERANKER_API_KEY "$reranker_key"
require_value AI_RAG_DOCLING_API_KEY "$docling_key"
require_value MYSQL_PASSWORD "$mysql_password"

if [[ "$local_mysql" != "true" && "$local_mysql" != "false" ]]; then
  printf 'RAG_BENCHMARK_LOCAL_MYSQL must be true or false\n' >&2
  exit 2
fi
if [[ "$local_mysql" == "true" ]]; then
  "$SCRIPT_DIR/prepare-local-rag-benchmark-mysql.sh"
  default_mysql_port=13307
  default_ssl_mode=DISABLED
  default_public_key_retrieval=true
else
  MYSQL_PASSWORD="$mysql_password" "$SCRIPT_DIR/ensure-rag-mysql-tunnel.sh"
  default_mysql_port=13306
  default_ssl_mode=REQUIRED
  default_public_key_retrieval=false
fi

export AI_RAG_EMBEDDING_API_KEY="$embedding_key"
export AI_RAG_RERANKER_API_KEY="$reranker_key"
export AI_RAG_DOCLING_API_KEY="$docling_key"
export MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
export MYSQL_PORT="${MYSQL_PORT:-$default_mysql_port}"
export MYSQL_DATABASE="${MYSQL_DATABASE:-ai_agent_scaffold}"
export MYSQL_USERNAME="${MYSQL_USERNAME:-ai_agent_app}"
export MYSQL_PASSWORD="${MYSQL_PASSWORD:-$mysql_password}"
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&serverTimezone=UTC&sslMode=$default_ssl_mode&allowPublicKeyRetrieval=$default_public_key_retrieval&connectTimeout=5000&socketTimeout=15000&tcpKeepAlive=true}"
export MYSQL_POOL_MIN_IDLE="${MYSQL_POOL_MIN_IDLE:-1}"
export MYSQL_POOL_MAX_SIZE="${MYSQL_POOL_MAX_SIZE:-6}"
export MYSQL_POOL_IDLE_TIMEOUT_MS="${MYSQL_POOL_IDLE_TIMEOUT_MS:-120000}"
export MYSQL_POOL_MAX_LIFETIME_MS="${MYSQL_POOL_MAX_LIFETIME_MS:-600000}"
export MYSQL_POOL_KEEPALIVE_MS="${MYSQL_POOL_KEEPALIVE_MS:-60000}"
export MYSQL_POOL_CONNECTION_TIMEOUT_MS="${MYSQL_POOL_CONNECTION_TIMEOUT_MS:-5000}"
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
export AI_RAG_QDRANT_TIMEOUT=3s
export AI_RAG_QDRANT_MAX_RETRIES=5
export AI_RAG_QDRANT_RETRY_INITIAL_BACKOFF=100ms
export AI_RAG_QDRANT_RETRY_MAX_BACKOFF=1s
export AI_RAG_QDRANT_TOTAL_TIMEOUT=30s
export AI_RAG_EMBEDDING_ENDPOINT="${AI_RAG_EMBEDDING_ENDPOINT:-http://103.205.240.84:8081}"
export AI_RAG_EMBEDDING_BATCH_SIZE=8
export AI_RAG_EMBEDDING_REQUEST_TIMEOUT=10s
export AI_RAG_EMBEDDING_TIMEOUT=30s
export AI_RAG_EMBEDDING_MAX_RETRIES=5
export AI_RAG_EMBEDDING_RETRY_INITIAL_BACKOFF=500ms
export AI_RAG_EMBEDDING_RETRY_MAX_BACKOFF=4s
export AI_RAG_RERANKER_ENDPOINT="${AI_RAG_RERANKER_ENDPOINT:-http://103.205.240.84:8082}"
export AI_RAG_RERANKER_REQUEST_BATCH_SIZE="${AI_RAG_RERANKER_REQUEST_BATCH_SIZE:-3}"
# Top-10 is sent as 3/3/3/1 sequential requests. The benchmark deadline must cover all
# four remote batches, while the per-request deadline avoids retrying an inference
# merely because the public response path occasionally exceeds ten seconds.
export AI_RAG_RERANKER_REQUEST_TIMEOUT="${AI_RAG_RERANKER_REQUEST_TIMEOUT:-20s}"
export AI_RAG_RERANKER_TIMEOUT="${AI_RAG_RERANKER_TIMEOUT:-60s}"
export AI_RAG_RERANKER_MAX_RETRIES="${AI_RAG_RERANKER_MAX_RETRIES:-2}"
export AI_RAG_RERANKER_RETRY_INITIAL_BACKOFF="${AI_RAG_RERANKER_RETRY_INITIAL_BACKOFF:-100ms}"
export AI_RAG_RERANKER_RETRY_MAX_BACKOFF="${AI_RAG_RERANKER_RETRY_MAX_BACKOFF:-1s}"
export AI_RAG_DOCLING_ENDPOINT="${AI_RAG_DOCLING_ENDPOINT:-http://103.205.240.84:5001/v1}"
export AI_RAG_WORKER_LEASE_DURATION_MS=600000
export AI_RAG_WORKER_HEARTBEAT_INTERVAL_MS=30000
export OBJECT_STORAGE_TYPE=local
export OBJECT_STORAGE_LOCAL_ROOT="${OBJECT_STORAGE_LOCAL_ROOT:-/tmp/ai-agent-rag-benchmark/object-storage}"
export OBS_LOG_DIR="${OBS_LOG_DIR:-/tmp/ai-agent-rag-benchmark/log}"
export SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false
export SPRING_CLOUD_NACOS_CONFIG_ENABLED=false

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
  --spring.cloud.nacos.config.enabled=false \
  --spring.cloud.nacos.discovery.enabled=false \
  "$@"
