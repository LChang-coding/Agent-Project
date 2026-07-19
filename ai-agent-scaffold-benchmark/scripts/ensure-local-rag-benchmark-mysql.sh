#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLIST="$SCRIPT_DIR/rag-local-mysql.plist"
LABEL="cn.bugstack.ai.rag-local-mysql"
DOMAIN="gui/$(id -u)"
SERVICE="$DOMAIN/$LABEL"
DATA_DIR="/tmp/ai-agent-rag-benchmark/mysql-data"
SOCKET="/tmp/ai-agent-rag-benchmark/mysql.sock"

for command_name in launchctl mysql mysqld perl; do
  command -v "$command_name" >/dev/null || {
    printf 'required local MySQL command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ ! -r "$PLIST" ]]; then
  printf 'local RAG benchmark MySQL launch agent is unavailable\n' >&2
  exit 2
fi

probe_mysql() {
  perl -e 'alarm shift; exec @ARGV' 5 \
    mysql --connect-timeout=3 --protocol=socket --socket="$SOCKET" -u root \
      --batch --skip-column-names -e 'SELECT 1' >/dev/null 2>&1
}

if [[ ! -d "$DATA_DIR/mysql" ]]; then
  mkdir -p "$DATA_DIR"
  mysqld --initialize-insecure --datadir="$DATA_DIR"
fi

if ! launchctl print "$SERVICE" >/dev/null 2>&1; then
  launchctl bootstrap "$DOMAIN" "$PLIST"
elif probe_mysql; then
  printf 'local RAG benchmark MySQL healthy: 127.0.0.1:13307\n'
  exit 0
else
  launchctl kickstart -k "$SERVICE" >/dev/null
fi

for _ in {1..15}; do
  if probe_mysql; then
    printf 'local RAG benchmark MySQL healthy: 127.0.0.1:13307\n'
    exit 0
  fi
  sleep 1
done

printf 'local RAG benchmark MySQL did not become healthy; inspect /tmp/ai-agent-rag-benchmark/mysql-error.log\n' >&2
exit 1
