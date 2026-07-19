#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${RAG_BENCHMARK_BASE_URL:-http://127.0.0.1:8092/api}"
PREPARED_DIR="${RAG_BENCHMARK_PREPARED_DIR:?RAG_BENCHMARK_PREPARED_DIR is required}"
QUALITY_RUN_DIR="${RAG_BENCHMARK_QUALITY_RUN_DIR:?RAG_BENCHMARK_QUALITY_RUN_DIR is required}"
RUN_ID="${RAG_BENCHMARK_LOAD_RUN_ID:?RAG_BENCHMARK_LOAD_RUN_ID is required}"
OUTPUT_DIR="${RAG_BENCHMARK_LOAD_OUTPUT_DIR:?RAG_BENCHMARK_LOAD_OUTPUT_DIR is required}"
EVIDENCE_DIR="${RAG_BENCHMARK_RESOURCE_EVIDENCE_DIR:-$OUTPUT_DIR-evidence}"
CONCURRENCY_LEVELS="${RAG_BENCHMARK_LOAD_CONCURRENCY_LEVELS:-1}"
WARMUP_PER_VARIANT="${RAG_BENCHMARK_LOAD_WARMUP_PER_VARIANT:-5}"
REQUESTS_PER_VARIANT="${RAG_BENCHMARK_LOAD_REQUESTS_PER_VARIANT:-20}"
PHASE_TIMEOUT_SECONDS="${RAG_BENCHMARK_LOAD_PHASE_TIMEOUT_SECONDS:-7200}"
REQUEST_TIMEOUT_SECONDS="${RAG_BENCHMARK_REQUEST_TIMEOUT_SECONDS:-240}"
COLLECT_INTERVAL_SECONDS="${RAG_BENCHMARK_RESOURCE_INTERVAL_SECONDS:-5}"
USERNAME="${RAG_BENCHMARK_USERNAME:?RAG_BENCHMARK_USERNAME is required}"
PASSWORD="${RAG_BENCHMARK_PASSWORD:?RAG_BENCHMARK_PASSWORD is required}"
CLI_JAR="$PROJECT_ROOT/ai-agent-scaffold-benchmark/target/ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar"
APP_JAR="$PROJECT_ROOT/ai-agent-scaffold-app/target/ai-agent-scaffold-app.jar"

for command_name in curl jq java shasum git lsof; do
  command -v "$command_name" >/dev/null || {
    printf 'required load command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
for value_name in WARMUP_PER_VARIANT REQUESTS_PER_VARIANT PHASE_TIMEOUT_SECONDS REQUEST_TIMEOUT_SECONDS COLLECT_INTERVAL_SECONDS; do
  value="${!value_name}"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    printf '%s must be a positive integer\n' "$value_name" >&2
    exit 2
  fi
done
if [[ ! "$CONCURRENCY_LEVELS" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]]; then
  printf 'RAG_BENCHMARK_LOAD_CONCURRENCY_LEVELS must be comma-separated positive integers\n' >&2
  exit 2
fi
if [[ ! -r "$CLI_JAR" || ! -r "$APP_JAR" || ! -d "$PREPARED_DIR" || ! -d "$QUALITY_RUN_DIR"
      || -e "$OUTPUT_DIR" || -e "$EVIDENCE_DIR" ]]; then
  printf 'load inputs are unavailable or output/evidence already exists\n' >&2
  exit 2
fi

quality_manifest="$QUALITY_RUN_DIR/run-manifest.json"
quality_run="$QUALITY_RUN_DIR/run.jsonl"
quality_targets="$QUALITY_RUN_DIR/targets.json"
if [[ ! -r "$quality_manifest" || ! -r "$quality_run" || ! -r "$quality_targets" ]]; then
  printf 'completed quality artifacts are incomplete\n' >&2
  exit 2
fi
jq -e '.status == "completed" and (.errorCode == null)' "$quality_manifest" >/dev/null
jq -se 'length == 1200
  and (map(.variant + ":" + (.queryId|tostring)) | unique | length) == 1200
  and ([.[] | select((.errorCode // "") != "" or .degraded == true
        or (.rankedDocumentIds|length) == 0)] | length) == 0' "$quality_run" >/dev/null

app_pid="$(lsof -tiTCP:8092 -sTCP:LISTEN | head -1)"
if [[ ! "$app_pid" =~ ^[1-9][0-9]*$ ]]; then
  printf 'benchmark application is not listening on 8092\n' >&2
  exit 2
fi
"$SCRIPT_DIR/ensure-local-rag-benchmark-qdrant.sh"

login="$(jq -nc --arg username "$USERNAME" --arg password "$PASSWORD" \
  '{username:$username,password:$password}' \
  | curl --silent --show-error --fail-with-body --max-time 30 -H 'Content-Type: application/json' \
      --data-binary @- "$BASE_URL/v1/auth/login")"
export RAG_BENCHMARK_ACCESS_TOKEN="$(printf '%s' "$login" | jq -er 'select(.code=="0000") | .data.token')"
unset login

cli_sha256="$(shasum -a 256 "$CLI_JAR" | awk '{print $1}')"
app_sha256="$(shasum -a 256 "$APP_JAR" | awk '{print $1}')"
code_revision="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
evidence_manifest="$EVIDENCE_DIR/evidence-manifest.json"

collector_pid=""
cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$collector_pid" ]]; then
    kill "$collector_pid" 2>/dev/null || true
    wait "$collector_pid" 2>/dev/null || true
  fi
  unset RAG_BENCHMARK_ACCESS_TOKEN RAG_BENCHMARK_PASSWORD PASSWORD
}
trap cleanup EXIT INT TERM

"$SCRIPT_DIR/collect-rag-load-resources.sh" "$EVIDENCE_DIR" "$app_pid" "$COLLECT_INTERVAL_SECONDS" &
collector_pid=$!
for _ in {1..20}; do
  [[ -s "$EVIDENCE_DIR/local-process.jsonl" && -s "$EVIDENCE_DIR/remote-containers.jsonl" ]] && break
  kill -0 "$collector_pid" 2>/dev/null || break
  sleep 1
done
if [[ ! -s "$EVIDENCE_DIR/local-process.jsonl" || ! -s "$EVIDENCE_DIR/remote-containers.jsonl" ]]; then
  if ! kill -0 "$collector_pid" 2>/dev/null; then
    wait "$collector_pid" 2>/dev/null || true
    collector_pid=""
  fi
  printf 'resource sampler did not become ready\n' >&2
  exit 1
fi

set +e
java -jar "$CLI_JAR" load \
  --base-url "$BASE_URL" \
  --prepared "$PREPARED_DIR" \
  --targets "$quality_targets" \
  --out "$OUTPUT_DIR" \
  --run-id "$RUN_ID" \
  --code-revision "$code_revision" \
  --concurrency-levels "$CONCURRENCY_LEVELS" \
  --warmup-per-variant "$WARMUP_PER_VARIANT" \
  --requests-per-variant "$REQUESTS_PER_VARIANT" \
  --phase-timeout-seconds "$PHASE_TIMEOUT_SECONDS" \
  --request-timeout-seconds "$REQUEST_TIMEOUT_SECONDS" \
  --cli-jar-sha256 "$cli_sha256" \
  --app-jar-sha256 "$app_sha256" \
  --resource-evidence "$evidence_manifest"
load_status=$?
set -e

kill "$collector_pid" 2>/dev/null || true
wait "$collector_pid" 2>/dev/null || true
collector_pid=""
{
  date -u +%Y-%m-%dT%H:%M:%SZ
  ssh -o BatchMode=yes -o ConnectTimeout=10 RAG-Server \
    "docker inspect --format '{{.Name}}|{{.RestartCount}}|{{.State.OOMKilled}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}not_configured{{end}}|{{.Config.Image}}' \
      rag-mysql rag-prometheus rag-model-gateway rag-embedding rag-docling rag-reranker rag-qdrant rag-node-exporter"
} >"$EVIDENCE_DIR/remote-inspect-after.txt"
captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
local_sha="$(shasum -a 256 "$EVIDENCE_DIR/local-process.jsonl" | awk '{print $1}')"
remote_sha="$(shasum -a 256 "$EVIDENCE_DIR/remote-containers.jsonl" | awk '{print $1}')"
before_sha="$(shasum -a 256 "$EVIDENCE_DIR/remote-inspect-before.txt" | awk '{print $1}')"
after_sha="$(shasum -a 256 "$EVIDENCE_DIR/remote-inspect-after.txt" | awk '{print $1}')"
jq -n --arg ts "$captured_at" --arg runId "$RUN_ID" --argjson loadExitCode "$load_status" \
  --arg localFile "local-process.jsonl" --arg localSha256 "$local_sha" \
  --arg remoteFile "remote-containers.jsonl" --arg remoteSha256 "$remote_sha" \
  --arg beforeFile "remote-inspect-before.txt" --arg beforeSha256 "$before_sha" \
  --arg afterFile "remote-inspect-after.txt" --arg afterSha256 "$after_sha" \
  '{schemaVersion:1,capturedAt:$ts,runId:$runId,loadExitCode:$loadExitCode,
    files:[{name:$localFile,sha256:$localSha256},{name:$remoteFile,sha256:$remoteSha256},
      {name:$beforeFile,sha256:$beforeSha256},{name:$afterFile,sha256:$afterSha256}]}' \
  >"$evidence_manifest"

exit "$load_status"
