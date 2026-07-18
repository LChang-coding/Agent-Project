#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${RAG_BENCHMARK_BASE_URL:-http://127.0.0.1:8092/api}"
PREPARED_DIR="${RAG_BENCHMARK_PREPARED_DIR:-/tmp/rag-benchmark-mini-prepared-83f7809}"
RUN_ID="${RAG_BENCHMARK_RUN_ID:-mini-real-$(date -u +%Y%m%dT%H%M%SZ)}"
OUTPUT_DIR="${RAG_BENCHMARK_OUTPUT_DIR:-/tmp/rag-benchmark-$RUN_ID}"
CLI_JAR="$PROJECT_ROOT/ai-agent-scaffold-benchmark/target/ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar"

for command_name in curl jq openssl java git; do
  command -v "$command_name" >/dev/null || {
    printf 'required command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ ! -r "$CLI_JAR" || ! -d "$PREPARED_DIR" || -e "$OUTPUT_DIR" ]]; then
  printf 'CLI/prepared input is unavailable or output already exists\n' >&2
  exit 2
fi

auth_dir="$(mktemp -d /tmp/rag-benchmark-auth.XXXXXX)"
trap 'rm -rf "$auth_dir"; unset RAG_BENCHMARK_ACCESS_TOKEN' EXIT

suffix="$(date +%s)-$RANDOM"
username="rag_bench_$suffix"
password="$(openssl rand -hex 18)Aa1!"
email="$username@example.invalid"
phone="9$(printf '%010d' "$((RANDOM * RANDOM % 10000000000))")"

jq -nc \
  --arg tenant "RAG Benchmark $suffix" \
  --arg username "$username" \
  --arg password "$password" \
  --arg email "$email" \
  --arg phone "$phone" \
  '{tenantName:$tenant,username:$username,password:$password,nickname:"RAG Benchmark",email:$email,phone:$phone}' \
  >"$auth_dir/register-request.json"

curl --silent --show-error --fail-with-body --max-time 30 \
  -H 'Content-Type: application/json' \
  --data-binary "@$auth_dir/register-request.json" \
  "$BASE_URL/v1/auth/register" >"$auth_dir/register-response.json"
if [[ "$(jq -r '.code // empty' "$auth_dir/register-response.json")" != "0000" ]]; then
  jq -c '{code,info}' "$auth_dir/register-response.json" >&2
  exit 3
fi

jq -nc --arg username "$username" --arg password "$password" \
  '{username:$username,password:$password}' >"$auth_dir/login-request.json"
curl --silent --show-error --fail-with-body --max-time 30 \
  -H 'Content-Type: application/json' \
  --data-binary "@$auth_dir/login-request.json" \
  "$BASE_URL/v1/auth/login" >"$auth_dir/login-response.json"
if [[ "$(jq -r '.code // empty' "$auth_dir/login-response.json")" != "0000" ]]; then
  jq -c '{code,info}' "$auth_dir/login-response.json" >&2
  exit 3
fi
export RAG_BENCHMARK_ACCESS_TOKEN="$(jq -er '.data.token' "$auth_dir/login-response.json")"

printf 'starting runId=%s prepared=%s out=%s\n' "$RUN_ID" "$PREPARED_DIR" "$OUTPUT_DIR"
java -jar "$CLI_JAR" run \
  --base-url "$BASE_URL" \
  --prepared "$PREPARED_DIR" \
  --out "$OUTPUT_DIR" \
  --run-id "$RUN_ID" \
  --code-revision "$(git -C "$PROJECT_ROOT" rev-parse HEAD)" \
  --warmup-queries 0 \
  --poll-ms 1000 \
  --ingest-timeout-seconds 900 \
  --request-timeout-seconds 120
