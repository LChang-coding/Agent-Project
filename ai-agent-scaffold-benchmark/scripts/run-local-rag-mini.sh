#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${RAG_BENCHMARK_BASE_URL:-http://127.0.0.1:8092/api}"
PREPARED_DIR="${RAG_BENCHMARK_PREPARED_DIR:-/tmp/rag-benchmark-mini-prepared-83f7809}"
RUN_ID="${RAG_BENCHMARK_RUN_ID:-mini-real-$(date -u +%Y%m%dT%H%M%SZ)}"
OUTPUT_DIR="${RAG_BENCHMARK_OUTPUT_DIR:-/tmp/rag-benchmark-$RUN_ID}"
LOAD_ENABLED="${RAG_BENCHMARK_LOAD_ENABLED:-false}"
LOAD_RUN_ID="${RAG_BENCHMARK_LOAD_RUN_ID:-$RUN_ID-load}"
LOAD_OUTPUT_DIR="${RAG_BENCHMARK_LOAD_OUTPUT_DIR:-$OUTPUT_DIR-load}"
LOAD_CONCURRENCY_LEVELS="${RAG_BENCHMARK_LOAD_CONCURRENCY_LEVELS:-1,10}"
LOAD_WARMUP_PER_VARIANT="${RAG_BENCHMARK_LOAD_WARMUP_PER_VARIANT:-10}"
LOAD_REQUESTS_PER_VARIANT="${RAG_BENCHMARK_LOAD_REQUESTS_PER_VARIANT:-100}"
WARMUP_QUERIES="${RAG_BENCHMARK_WARMUP_QUERIES:-0}"
INGEST_TIMEOUT_SECONDS="${RAG_BENCHMARK_INGEST_TIMEOUT_SECONDS:-900}"
REQUEST_TIMEOUT_SECONDS="${RAG_BENCHMARK_REQUEST_TIMEOUT_SECONDS:-120}"
EXISTING_TARGETS="${RAG_BENCHMARK_EXISTING_TARGETS:-}"
RESUME_FROM="${RAG_BENCHMARK_RESUME_FROM:-}"
EXISTING_USERNAME="${RAG_BENCHMARK_USERNAME:-}"
EXISTING_PASSWORD="${RAG_BENCHMARK_PASSWORD:-}"
CLI_JAR="$PROJECT_ROOT/ai-agent-scaffold-benchmark/target/ai-agent-scaffold-benchmark-cli-jar-with-dependencies.jar"

for command_name in curl jq openssl java git; do
  command -v "$command_name" >/dev/null || {
    printf 'required command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ "$LOAD_ENABLED" != "true" && "$LOAD_ENABLED" != "false" ]]; then
  printf 'RAG_BENCHMARK_LOAD_ENABLED must be true or false\n' >&2
  exit 2
fi
if [[ ! "$REQUEST_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ || "$REQUEST_TIMEOUT_SECONDS" -gt 3600 ]]; then
  printf 'RAG_BENCHMARK_REQUEST_TIMEOUT_SECONDS must be an integer between 1 and 3600\n' >&2
  exit 2
fi
if [[ ! -r "$CLI_JAR" || ! -d "$PREPARED_DIR" || -e "$OUTPUT_DIR"
      || ("$LOAD_ENABLED" == "true" && -e "$LOAD_OUTPUT_DIR") ]]; then
  printf 'CLI/prepared input is unavailable or output already exists\n' >&2
  exit 2
fi
if [[ -n "$EXISTING_TARGETS" && (-z "$EXISTING_USERNAME" || -z "$EXISTING_PASSWORD") ]]; then
  printf 'existing targets require RAG_BENCHMARK_USERNAME and RAG_BENCHMARK_PASSWORD for the original tenant\n' >&2
  exit 2
fi
if [[ -n "$RESUME_FROM" && (-z "$EXISTING_TARGETS" || ! -d "$RESUME_FROM") ]]; then
  printf 'RAG_BENCHMARK_RESUME_FROM requires existing targets and a readable source directory\n' >&2
  exit 2
fi

auth_dir="$(mktemp -d /tmp/rag-benchmark-auth.XXXXXX)"
trap 'rm -rf "$auth_dir"; unset RAG_BENCHMARK_ACCESS_TOKEN RAG_BENCHMARK_USERNAME RAG_BENCHMARK_PASSWORD' EXIT

if [[ -n "$EXISTING_TARGETS" ]]; then
  username="$EXISTING_USERNAME"
  password="$EXISTING_PASSWORD"
else
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
export RAG_BENCHMARK_USERNAME="$username"
export RAG_BENCHMARK_PASSWORD="$password"
code_revision="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"

printf 'starting runId=%s prepared=%s out=%s\n' "$RUN_ID" "$PREPARED_DIR" "$OUTPUT_DIR"
command_name=run
extra_arguments=(--poll-ms 1000 --ingest-timeout-seconds "$INGEST_TIMEOUT_SECONDS")
if [[ -n "$EXISTING_TARGETS" ]]; then
  if [[ ! -r "$EXISTING_TARGETS" ]]; then
    printf 'existing targets file is unavailable\n' >&2
    exit 2
  fi
  command_name=evaluate
  extra_arguments=(--targets "$EXISTING_TARGETS")
  if [[ -n "$RESUME_FROM" ]]; then
    extra_arguments+=(--resume-from "$RESUME_FROM")
  fi
fi
java -jar "$CLI_JAR" "$command_name" \
  --base-url "$BASE_URL" \
  --prepared "$PREPARED_DIR" \
  --out "$OUTPUT_DIR" \
  --run-id "$RUN_ID" \
  --code-revision "$code_revision" \
  --warmup-queries "$WARMUP_QUERIES" \
  "${extra_arguments[@]}" \
  --request-timeout-seconds "$REQUEST_TIMEOUT_SECONDS"

if [[ "$LOAD_ENABLED" == "true" ]]; then
  printf 'starting load runId=%s levels=%s warmup=%s measured=%s out=%s\n' \
    "$LOAD_RUN_ID" "$LOAD_CONCURRENCY_LEVELS" "$LOAD_WARMUP_PER_VARIANT" \
    "$LOAD_REQUESTS_PER_VARIANT" "$LOAD_OUTPUT_DIR"
  java -jar "$CLI_JAR" load \
    --base-url "$BASE_URL" \
    --prepared "$PREPARED_DIR" \
    --targets "$OUTPUT_DIR/targets.json" \
    --out "$LOAD_OUTPUT_DIR" \
    --run-id "$LOAD_RUN_ID" \
    --code-revision "$code_revision" \
    --concurrency-levels "$LOAD_CONCURRENCY_LEVELS" \
    --warmup-per-variant "$LOAD_WARMUP_PER_VARIANT" \
    --requests-per-variant "$LOAD_REQUESTS_PER_VARIANT" \
    --phase-timeout-seconds 1800 \
    --request-timeout-seconds "$REQUEST_TIMEOUT_SECONDS"
fi
