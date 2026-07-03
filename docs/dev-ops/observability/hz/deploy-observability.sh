#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [[ ! -f .env ]]; then
  echo ".env not found. Copy .env.example to .env and set Grafana credentials first."
  exit 1
fi

mkdir -p data/grafana data/loki
docker compose --env-file .env up -d
docker compose ps
