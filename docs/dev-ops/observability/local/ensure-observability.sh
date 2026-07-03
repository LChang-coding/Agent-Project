#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
WORKSPACE_ROOT="$(cd "${PROJECT_ROOT}/.." && pwd)"
RUN_DIR="${RUN_DIR:-/tmp/ai-agent-scaffold-observability}"

LOCAL_PORT="${LOCAL_PORT:-13100}"
LOKI_WRITE_URL="${LOKI_WRITE_URL:-http://127.0.0.1:${LOCAL_PORT}/loki/api/v1/push}"
OBS_LOG_DIR="${OBS_LOG_DIR:-${PROJECT_ROOT}/data/log}"
OBS_EXTRA_LOG_DIR="${OBS_EXTRA_LOG_DIR:-${WORKSPACE_ROOT}/data/log}"
OBS_LOG_GLOB="${OBS_LOG_GLOB:-${OBS_LOG_DIR}/*.log}"
OBS_EXTRA_LOG_GLOB="${OBS_EXTRA_LOG_GLOB:-${OBS_EXTRA_LOG_DIR}/*.log}"

mkdir -p "${RUN_DIR}" "${OBS_LOG_DIR}" "${OBS_EXTRA_LOG_DIR}"

is_loki_ready() {
  curl -fsS --max-time 3 "http://127.0.0.1:${LOCAL_PORT}/ready" >/dev/null 2>&1
}

ensure_loki_tunnel() {
  if is_loki_ready; then
    echo "Loki tunnel already ready: http://127.0.0.1:${LOCAL_PORT}"
    return
  fi

  echo "Starting Loki SSH tunnel on local port ${LOCAL_PORT}..."
  nohup env \
    LOCAL_PORT="${LOCAL_PORT}" \
    REMOTE_HOST="${REMOTE_HOST:-127.0.0.1}" \
    REMOTE_PORT="${REMOTE_PORT:-3100}" \
    SSH_TARGET="${SSH_TARGET:-root@69.165.65.123}" \
    SSH_KEY="${SSH_KEY:-$HOME/dadaikuai}" \
    "${SCRIPT_DIR}/start-loki-tunnel.sh" \
    > "${RUN_DIR}/loki-tunnel.log" 2>&1 &

  sleep 2
  if ! is_loki_ready; then
    echo "Loki tunnel did not become ready. See ${RUN_DIR}/loki-tunnel.log"
    exit 1
  fi

  echo "Loki tunnel started: http://127.0.0.1:${LOCAL_PORT}"
}

ensure_alloy() {
  if pgrep -f "alloy run ${SCRIPT_DIR}/config.alloy" >/dev/null 2>&1; then
    echo "Alloy already running for ${SCRIPT_DIR}/config.alloy"
    return
  fi

  if ! command -v alloy >/dev/null 2>&1; then
    echo "Grafana Alloy is not installed. Install it first, then rerun this script."
    exit 1
  fi

  echo "Starting Alloy..."
  echo "OBS_LOG_GLOB=${OBS_LOG_GLOB}"
  echo "OBS_EXTRA_LOG_GLOB=${OBS_EXTRA_LOG_GLOB}"
  nohup env \
    OBS_LOG_DIR="${OBS_LOG_DIR}" \
    OBS_LOG_GLOB="${OBS_LOG_GLOB}" \
    OBS_EXTRA_LOG_DIR="${OBS_EXTRA_LOG_DIR}" \
    OBS_EXTRA_LOG_GLOB="${OBS_EXTRA_LOG_GLOB}" \
    LOKI_WRITE_URL="${LOKI_WRITE_URL}" \
    "${SCRIPT_DIR}/start-alloy.sh" \
    > "${RUN_DIR}/alloy.log" 2>&1 &

  sleep 2
  if ! pgrep -f "alloy run ${SCRIPT_DIR}/config.alloy" >/dev/null 2>&1; then
    echo "Alloy did not stay running. See ${RUN_DIR}/alloy.log"
    exit 1
  fi

  echo "Alloy started. Logs: ${RUN_DIR}/alloy.log"
}

ensure_loki_tunnel
ensure_alloy

echo "Observability is ready."
