#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CODEX_FILE="$PROJECT_ROOT/codex.md"
PLIST="$SCRIPT_DIR/rag-mysql-tunnel.plist"
LABEL="cn.bugstack.ai.rag-mysql-tunnel"
DOMAIN="gui/$(id -u)"
SERVICE="$DOMAIN/$LABEL"
LOCAL_PORT="${RAG_MYSQL_TUNNEL_LOCAL_PORT:-13306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-ai_agent_scaffold}"
MYSQL_USERNAME="${MYSQL_USERNAME:-ai_agent_app}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"

for command_name in launchctl mysql perl; do
  command -v "$command_name" >/dev/null || {
    printf 'required tunnel command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ "$LOCAL_PORT" != "13306" ]]; then
  printf 'the bundled launch agent currently supports only local port 13306\n' >&2
  exit 2
fi
if [[ ! -r "$PLIST" ]]; then
  printf 'RAG MySQL tunnel launch agent is unavailable\n' >&2
  exit 2
fi
if [[ -z "$MYSQL_PASSWORD" && -r "$CODEX_FILE" ]]; then
  MYSQL_PASSWORD="$(awk -F'|' '/root` 或应用配置中的数据库用户/ {
    value = $4
    gsub(/[`[:space:]]/, "", value)
    print value
    exit
  }' "$CODEX_FILE")"
fi
if [[ -z "$MYSQL_PASSWORD" ]]; then
  printf 'RAG MySQL tunnel health check credential is unavailable\n' >&2
  exit 2
fi

probe_mysql() {
  MYSQL_PWD="$MYSQL_PASSWORD" perl -e 'alarm shift; exec @ARGV' 5 \
    mysql --connect-timeout=3 --ssl-mode=REQUIRED \
      -h 127.0.0.1 -P "$LOCAL_PORT" -u "$MYSQL_USERNAME" -D "$MYSQL_DATABASE" \
      --batch --skip-column-names -e 'SELECT 1' >/dev/null 2>&1
}

if ! launchctl print "$SERVICE" >/dev/null 2>&1; then
  launchctl bootstrap "$DOMAIN" "$PLIST"
elif probe_mysql; then
  printf 'RAG MySQL tunnel healthy: 127.0.0.1:%s\n' "$LOCAL_PORT"
  exit 0
else
  printf 'RAG MySQL tunnel is stale; restarting managed forward\n' >&2
  launchctl kickstart -k "$SERVICE" >/dev/null
fi

for _ in {1..10}; do
  if probe_mysql; then
    printf 'RAG MySQL tunnel healthy: 127.0.0.1:%s\n' "$LOCAL_PORT"
    exit 0
  fi
  sleep 1
done

printf 'RAG MySQL tunnel did not become ready; inspect /tmp/ai-agent-rag-benchmark/mysql-tunnel.err.log\n' >&2
exit 1
