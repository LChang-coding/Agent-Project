#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:?usage: collect-rag-load-resources.sh OUTPUT_DIR APP_PID [INTERVAL_SECONDS]}"
APP_PID="${2:?usage: collect-rag-load-resources.sh OUTPUT_DIR APP_PID [INTERVAL_SECONDS]}"
INTERVAL_SECONDS="${3:-5}"

if [[ ! "$APP_PID" =~ ^[1-9][0-9]*$ || ! "$INTERVAL_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  printf 'APP_PID and INTERVAL_SECONDS must be positive integers\n' >&2
  exit 2
fi
for command_name in jq ps ssh; do
  command -v "$command_name" >/dev/null || {
    printf 'required resource sampler command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ -e "$OUTPUT_DIR" ]]; then
  printf 'resource evidence directory must not already exist\n' >&2
  exit 2
fi
mkdir -p "$OUTPUT_DIR"

SSH_CONTROL_PATH="/tmp/rag-resource-ssh-$$.sock"
SSH_OPTIONS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 -S "$SSH_CONTROL_PATH")

close_ssh_master() {
  ssh -S "$SSH_CONTROL_PATH" -O exit RAG-Server >/dev/null 2>&1 || true
  rm -f "$SSH_CONTROL_PATH"
}
trap close_ssh_master EXIT INT TERM
master_ready=false
for attempt in 1 2 3; do
  if ssh -M -o ControlPersist=no "${SSH_OPTIONS[@]}" -fN RAG-Server; then
    master_ready=true
    break
  fi
  printf 'SSH control master attempt %d of 3 failed\n' "$attempt" >&2
  sleep 2
done
if [[ "$master_ready" != "true" ]]; then
  printf 'SSH control master did not become ready after 3 attempts\n' >&2
  exit 1
fi

capture_remote_inspect() {
  local target_path="$1"
  local temporary_path="$target_path.tmp"
  {
    date -u +%Y-%m-%dT%H:%M:%SZ
    ssh "${SSH_OPTIONS[@]}" RAG-Server \
      "docker inspect --format '{{.Name}}|{{.RestartCount}}|{{.State.OOMKilled}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}not_configured{{end}}|{{.Config.Image}}' \
        rag-mysql rag-prometheus rag-model-gateway rag-embedding rag-docling rag-reranker rag-qdrant rag-node-exporter" </dev/null
  } >"$temporary_path"
  mv "$temporary_path" "$target_path"
}

inspect_path="$OUTPUT_DIR/remote-inspect-before.txt"
inspect_captured=false
for attempt in 1 2 3; do
  if capture_remote_inspect "$inspect_path"; then
    inspect_captured=true
    break
  fi
  printf 'remote inspect attempt %d of 3 failed\n' "$attempt" >&2
  sleep 1
done
if [[ "$inspect_captured" != "true" ]]; then
  printf 'remote inspect did not become ready after 3 attempts\n' >&2
  exit 1
fi

local_sampler() {
  while kill -0 "$APP_PID" 2>/dev/null; do
    captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    process_row="$(ps -o pid=,%cpu=,rss=,etime= -p "$APP_PID" | awk '{$1=$1; print}')"
    thread_count="$(ps -M -p "$APP_PID" | tail -n +2 | wc -l | awk '{$1=$1; print}')"
    gc_row=""
    if command -v jstat >/dev/null; then
      gc_row="$(jstat -gcutil "$APP_PID" 2>/dev/null | tail -1 | awk '{$1=$1; print}' || true)"
    fi
    jq -nc --arg ts "$captured_at" --arg process "$process_row" --arg gc "$gc_row" \
      --argjson threadCount "$thread_count" \
      '{capturedAt:$ts,appProcess:$process,threadCount:$threadCount,jstatGcUtil:$gc}'
    sleep "$INTERVAL_SECONDS"
  done
}

remote_sampler() {
  while kill -0 "$APP_PID" 2>/dev/null; do
    ssh "${SSH_OPTIONS[@]}" RAG-Server \
      "docker stats --no-stream --format '{{json .}}' \
        rag-mysql rag-prometheus rag-model-gateway rag-embedding rag-docling rag-reranker rag-qdrant rag-node-exporter \
        | jq -sc --arg ts \"\$(date -u +%Y-%m-%dT%H:%M:%SZ)\" '{capturedAt:\$ts,containers:.}'" </dev/null || true
    sleep "$INTERVAL_SECONDS"
  done
}

local_sampler >"$OUTPUT_DIR/local-process.jsonl" &
local_pid=$!
remote_sampler >"$OUTPUT_DIR/remote-containers.jsonl" 2>"$OUTPUT_DIR/remote-sampler.err.log" &
remote_pid=$!

shutdown() {
  trap - EXIT INT TERM
  kill "$local_pid" "$remote_pid" 2>/dev/null || true
  wait "$local_pid" "$remote_pid" 2>/dev/null || true
  capture_remote_inspect "$OUTPUT_DIR/remote-inspect-after.txt" || true
  close_ssh_master
}
trap shutdown EXIT INT TERM
wait "$local_pid" "$remote_pid"
