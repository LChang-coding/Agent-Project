#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

export OBS_LOG_DIR="${OBS_LOG_DIR:-${PROJECT_ROOT}/data/log}"
export OBS_LOG_GLOB="${OBS_LOG_GLOB:-${OBS_LOG_DIR}/*.log}"
export LOKI_WRITE_URL="${LOKI_WRITE_URL:-http://127.0.0.1:13100/loki/api/v1/push}"

if ! command -v alloy >/dev/null 2>&1; then
  echo "Grafana Alloy is not installed. Install it first, then rerun this script."
  exit 1
fi

mkdir -p "${OBS_LOG_DIR}"
echo "OBS_LOG_DIR=${OBS_LOG_DIR}"
echo "OBS_LOG_GLOB=${OBS_LOG_GLOB}"
echo "LOKI_WRITE_URL=${LOKI_WRITE_URL}"

alloy run "${SCRIPT_DIR}/config.alloy"
