#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-$HOME/middleware/minio}"

mkdir -p "$DEPLOY_DIR/data"
cd "$DEPLOY_DIR"

if [[ ! -f .env ]]; then
  SECRET="$(openssl rand -base64 30 | tr -d '=+/' | cut -c1-32)"
  cat > .env <<ENV
MINIO_ROOT_USER=ai_agent_admin
MINIO_ROOT_PASSWORD=$SECRET
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
ENV
  chmod 600 .env
fi

SOURCE_COMPOSE="${SOURCE_DIR:-.}/docker-compose.yml"
TARGET_COMPOSE="$DEPLOY_DIR/docker-compose.yml"
if [[ "$(cd "$(dirname "$SOURCE_COMPOSE")" && pwd)/$(basename "$SOURCE_COMPOSE")" != "$(cd "$(dirname "$TARGET_COMPOSE")" && pwd)/$(basename "$TARGET_COMPOSE")" ]]; then
  cp "$SOURCE_COMPOSE" "$TARGET_COMPOSE"
fi
docker compose up -d
docker compose ps
