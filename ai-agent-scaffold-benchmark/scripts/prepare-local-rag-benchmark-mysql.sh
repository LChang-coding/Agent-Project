#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CODEX_FILE="$PROJECT_ROOT/codex.md"
DUMP_FILE="${RAG_BENCHMARK_MYSQL_DUMP:-/tmp/ai-agent-scaffold-mysql-20260719T2016.sql}"
EXPECTED_DUMP_SHA256="ffc2bae94a16d4d68c7a468bb63f28e3ec8ba91e54b8c5c1a6f12b26d8e86aba"
SOCKET="/tmp/ai-agent-rag-benchmark/mysql.sock"
DATABASE="ai_agent_scaffold"
USERNAME="ai_agent_app"

for command_name in mysql shasum awk; do
  command -v "$command_name" >/dev/null || {
    printf 'required local MySQL preparation command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ ! -r "$CODEX_FILE" || ! -r "$DUMP_FILE" ]]; then
  printf 'codex.md or verified MySQL dump is unavailable\n' >&2
  exit 2
fi
actual_sha256="$(shasum -a 256 "$DUMP_FILE" | awk '{print $1}')"
if [[ "$actual_sha256" != "$EXPECTED_DUMP_SHA256" ]]; then
  printf 'local MySQL dump SHA-256 mismatch\n' >&2
  exit 2
fi
password="$(awk -F'|' '/root` 或应用配置中的数据库用户/ {
  value = $4
  gsub(/[`[:space:]]/, "", value)
  print value
  exit
}' "$CODEX_FILE")"
if [[ ! "$password" =~ ^[A-Za-z0-9._-]{8,128}$ ]]; then
  printf 'local MySQL application credential format is unsupported\n' >&2
  exit 2
fi

"$SCRIPT_DIR/ensure-local-rag-benchmark-mysql.sh"
mysql_root=(mysql --protocol=socket --socket="$SOCKET" -u root --batch --skip-column-names)
"${mysql_root[@]}" -e "CREATE DATABASE IF NOT EXISTS $DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
table_count="$("${mysql_root[@]}" -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DATABASE';")"
if [[ "$table_count" == "0" ]]; then
  "${mysql_root[@]}" "$DATABASE" <"$DUMP_FILE"
  table_count="$("${mysql_root[@]}" -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DATABASE';")"
fi
if [[ "$table_count" != "34" ]]; then
  printf 'local MySQL schema has unexpected table count: %s\n' "$table_count" >&2
  exit 1
fi

"${mysql_root[@]}" -e "CREATE USER IF NOT EXISTS '$USERNAME'@'127.0.0.1' IDENTIFIED BY '$password'; ALTER USER '$USERNAME'@'127.0.0.1' IDENTIFIED BY '$password'; GRANT SELECT,INSERT,UPDATE,DELETE ON $DATABASE.* TO '$USERNAME'@'127.0.0.1'; FLUSH PRIVILEGES;"
unset password

printf 'local RAG benchmark MySQL prepared: tables=%s dumpSha256=%s\n' "$table_count" "$actual_sha256"
