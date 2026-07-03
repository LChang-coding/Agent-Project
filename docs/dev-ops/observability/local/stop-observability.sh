#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_PORT="${LOCAL_PORT:-13100}"

if pgrep -f "alloy run ${SCRIPT_DIR}/config.alloy" >/dev/null 2>&1; then
  pkill -f "alloy run ${SCRIPT_DIR}/config.alloy"
  echo "Stopped Alloy."
else
  echo "Alloy is not running."
fi

SSH_PIDS="$(lsof -tiTCP:"${LOCAL_PORT}" -sTCP:LISTEN || true)"
if [[ -n "${SSH_PIDS}" ]]; then
  echo "${SSH_PIDS}" | xargs kill
  echo "Stopped Loki SSH tunnel on local port ${LOCAL_PORT}."
else
  echo "Loki SSH tunnel is not running on local port ${LOCAL_PORT}."
fi
