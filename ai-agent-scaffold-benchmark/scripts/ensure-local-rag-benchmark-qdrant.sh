#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLIST="$SCRIPT_DIR/rag-qdrant-tunnel.plist"
LABEL="cn.bugstack.ai.rag-qdrant-tunnel"
DOMAIN="gui/$(id -u)"
SERVICE="$DOMAIN/$LABEL"
LOCAL_PORT="${RAG_QDRANT_TUNNEL_LOCAL_PORT:-16333}"
COLLECTION="${AI_RAG_QDRANT_COLLECTION:-ai_agent_rag_benchmark_v1}"

for command_name in launchctl curl jq; do
  command -v "$command_name" >/dev/null || {
    printf 'required Qdrant tunnel command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ "$LOCAL_PORT" != "16333" ]]; then
  printf 'the bundled Qdrant launch agent currently supports only local port 16333\n' >&2
  exit 2
fi
if [[ ! -r "$PLIST" ]]; then
  printf 'RAG Qdrant tunnel launch agent is unavailable\n' >&2
  exit 2
fi
mkdir -p /tmp/ai-agent-rag-benchmark

probe_qdrant() {
  curl --silent --show-error --fail --max-time 5 \
    "http://127.0.0.1:$LOCAL_PORT/healthz" >/dev/null 2>&1 \
    && curl --silent --show-error --fail --max-time 5 \
      "http://127.0.0.1:$LOCAL_PORT/collections/$COLLECTION" \
      | jq -e '.status == "ok" and .result.status == "green" and .result.points_count > 0' \
        >/dev/null 2>&1
}

if ! launchctl print "$SERVICE" >/dev/null 2>&1; then
  launchctl bootstrap "$DOMAIN" "$PLIST"
elif probe_qdrant; then
  printf 'RAG Qdrant tunnel healthy: 127.0.0.1:%s\n' "$LOCAL_PORT"
  exit 0
else
  printf 'RAG Qdrant tunnel is stale; restarting managed forward\n' >&2
  launchctl kickstart -k "$SERVICE" >/dev/null
fi

for _ in {1..10}; do
  if probe_qdrant; then
    printf 'RAG Qdrant tunnel healthy: 127.0.0.1:%s\n' "$LOCAL_PORT"
    exit 0
  fi
  sleep 1
done

printf 'RAG Qdrant tunnel did not become ready; inspect /tmp/ai-agent-rag-benchmark/qdrant-tunnel.err.log\n' >&2
exit 1
