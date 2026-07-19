#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLIST="$SCRIPT_DIR/rag-mysql-tunnel.plist"
LABEL="cn.bugstack.ai.rag-mysql-tunnel"
DOMAIN="gui/$(id -u)"
SERVICE="$DOMAIN/$LABEL"
LOCAL_PORT="${RAG_MYSQL_TUNNEL_LOCAL_PORT:-13306}"

for command_name in launchctl nc; do
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

if ! launchctl print "$SERVICE" >/dev/null 2>&1; then
  launchctl bootstrap "$DOMAIN" "$PLIST"
else
  launchctl kickstart "$SERVICE" >/dev/null
fi

for _ in {1..20}; do
  if nc -z -w 1 127.0.0.1 "$LOCAL_PORT" >/dev/null 2>&1; then
    printf 'RAG MySQL tunnel ready: 127.0.0.1:%s\n' "$LOCAL_PORT"
    exit 0
  fi
  sleep 1
done

printf 'RAG MySQL tunnel did not become ready; inspect /tmp/ai-agent-rag-benchmark/mysql-tunnel.err.log\n' >&2
exit 1
