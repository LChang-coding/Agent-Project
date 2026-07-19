#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${RAG_FORMAT_BASE_URL:-http://127.0.0.1:8092/api}"
FIXTURE_DIR="${RAG_FORMAT_FIXTURE_DIR:-$PROJECT_ROOT/docs/rag/evaluation-data/format-e2e}"
OUTPUT_DIR="${RAG_FORMAT_OUTPUT_DIR:?RAG_FORMAT_OUTPUT_DIR is required}"
EVIDENCE_DIR="${RAG_FORMAT_EVIDENCE_DIR:-$OUTPUT_DIR-evidence}"
RUN_ID="${RAG_FORMAT_RUN_ID:?RAG_FORMAT_RUN_ID is required}"
APP_JAR="$PROJECT_ROOT/ai-agent-scaffold-app/target/ai-agent-scaffold-app.jar"
INTERVAL_SECONDS="${RAG_FORMAT_RESOURCE_INTERVAL_SECONDS:-2}"

for command_name in curl jq lsof python3 shasum ssh; do
  command -v "$command_name" >/dev/null || {
    printf 'required format E2E command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ -e "$OUTPUT_DIR" || -e "$EVIDENCE_DIR" ]]; then
  printf 'format E2E output or evidence directory already exists\n' >&2
  exit 2
fi
app_pid="$(lsof -tiTCP:8092 -sTCP:LISTEN | head -1)"
if [[ ! "$app_pid" =~ ^[1-9][0-9]*$ ]]; then
  printf 'final RAG application is not listening on 8092\n' >&2
  exit 2
fi

collector_pid=""
cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$collector_pid" ]]; then
    kill "$collector_pid" 2>/dev/null || true
    wait "$collector_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

"$SCRIPT_DIR/collect-rag-load-resources.sh" "$EVIDENCE_DIR" "$app_pid" "$INTERVAL_SECONDS" &
collector_pid=$!
for _ in {1..60}; do
  [[ -s "$EVIDENCE_DIR/local-process.jsonl" && -s "$EVIDENCE_DIR/remote-containers.jsonl" ]] && break
  kill -0 "$collector_pid" 2>/dev/null || break
  sleep 1
done
if [[ ! -s "$EVIDENCE_DIR/local-process.jsonl" || ! -s "$EVIDENCE_DIR/remote-containers.jsonl" ]]; then
  printf 'format E2E resource sampler did not become ready\n' >&2
  exit 1
fi

set +e
python3 "$SCRIPT_DIR/run-rag-format-e2e.py" \
  --base-url "$BASE_URL" \
  --fixture-dir "$FIXTURE_DIR" \
  --out "$OUTPUT_DIR" \
  --run-id "$RUN_ID" \
  --ingest-timeout-seconds 900 \
  --request-timeout-seconds 180 \
  --app-jar "$APP_JAR"
run_status=$?
set -e

kill "$collector_pid" 2>/dev/null || true
wait "$collector_pid" 2>/dev/null || true
collector_pid=""
if [[ ! -s "$EVIDENCE_DIR/remote-inspect-after.txt" ]]; then
  printf 'format E2E resource sampler did not capture final container state\n' >&2
  exit 1
fi

files=(local-process.jsonl remote-containers.jsonl remote-inspect-before.txt remote-inspect-after.txt)
jq_files='[]'
for name in "${files[@]}"; do
  hash="$(shasum -a 256 "$EVIDENCE_DIR/$name" | awk '{print $1}')"
  bytes="$(wc -c <"$EVIDENCE_DIR/$name" | awk '{$1=$1; print}')"
  jq_files="$(jq -c --arg name "$name" --arg sha256 "$hash" --argjson bytes "$bytes" \
    '. + [{name:$name,sha256:$sha256,bytes:$bytes}]' <<<"$jq_files")"
done
jq -n --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg runId "$RUN_ID" \
  --argjson runExitCode "$run_status" --argjson files "$jq_files" \
  '{schemaVersion:1,capturedAt:$ts,runId:$runId,runExitCode:$runExitCode,files:$files}' \
  >"$EVIDENCE_DIR/evidence-manifest.json"

exit "$run_status"
