#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
OFFICIAL_SQL_URL="https://raw.githubusercontent.com/xuxueli/xxl-job/v3.4.0/doc/db/tables_xxl_job.sql"
OFFICIAL_SQL_SHA256="946bb73716e3ae9fd1c2d9b5083e8d28c84c3b9e0f11b44a31b1b82bb52f9cba"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}，请先复制 .env.example 并替换全部 change-me" >&2
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

for variable in XXL_JOB_MYSQL_ROOT_PASSWORD XXL_JOB_MYSQL_PASSWORD XXL_JOB_ACCESS_TOKEN XXL_JOB_ADMIN_PASSWORD; do
  value="${!variable:-}"
  if [[ -z "${value}" || "${value}" == change-me* ]]; then
    echo "${variable} 未配置安全值" >&2
    exit 1
  fi
done

if (( ${#XXL_JOB_ADMIN_PASSWORD} < 4 || ${#XXL_JOB_ADMIN_PASSWORD} > 20 )); then
  echo "XXL_JOB_ADMIN_PASSWORD 必须为 4~20 个字符，XXL-JOB 3.4.0 登录页最多接收 20 个字符" >&2
  exit 1
fi

compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
"${compose[@]}" up -d xxl-job-mysql

for _ in {1..60}; do
  if "${compose[@]}" exec -T -e MYSQL_PWD="${XXL_JOB_MYSQL_ROOT_PASSWORD}" \
      xxl-job-mysql mysqladmin ping -h 127.0.0.1 -uroot --silent >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if ! "${compose[@]}" exec -T -e MYSQL_PWD="${XXL_JOB_MYSQL_ROOT_PASSWORD}" \
    xxl-job-mysql mysqladmin ping -h 127.0.0.1 -uroot --silent >/dev/null 2>&1; then
  echo "XXL-JOB MySQL 未在预期时间内就绪" >&2
  exit 1
fi

table_count="$("${compose[@]}" exec -T -e MYSQL_PWD="${XXL_JOB_MYSQL_ROOT_PASSWORD}" \
  xxl-job-mysql mysql -N -uroot -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='xxl_job' AND table_name='xxl_job_info';")"

tmp_sql="$(mktemp)"
trap 'rm -f "${tmp_sql}"' EXIT
if [[ "${table_count}" == "0" ]]; then
  curl -fsSL "${OFFICIAL_SQL_URL}" -o "${tmp_sql}"
  if command -v sha256sum >/dev/null 2>&1; then
    actual_sha="$(sha256sum "${tmp_sql}" | awk '{print $1}')"
  else
    actual_sha="$(shasum -a 256 "${tmp_sql}" | awk '{print $1}')"
  fi
  if [[ "${actual_sha}" != "${OFFICIAL_SQL_SHA256}" ]]; then
    echo "XXL-JOB 官方初始化 SQL 摘要不匹配" >&2
    exit 1
  fi
  "${compose[@]}" exec -T -e MYSQL_PWD="${XXL_JOB_MYSQL_ROOT_PASSWORD}" \
    xxl-job-mysql mysql -uroot < "${tmp_sql}"
fi

if command -v sha256sum >/dev/null 2>&1; then
  admin_password_hash="$(printf '%s' "${XXL_JOB_ADMIN_PASSWORD}" | sha256sum | awk '{print $1}')"
else
  admin_password_hash="$(printf '%s' "${XXL_JOB_ADMIN_PASSWORD}" | shasum -a 256 | awk '{print $1}')"
fi
"${compose[@]}" exec -T -e MYSQL_PWD="${XXL_JOB_MYSQL_ROOT_PASSWORD}" xxl-job-mysql mysql -uroot xxl_job \
  -e "UPDATE xxl_job_user SET password='${admin_password_hash}', token=NULL WHERE username='admin';"
"${compose[@]}" exec -T -e MYSQL_PWD="${XXL_JOB_MYSQL_ROOT_PASSWORD}" xxl-job-mysql mysql -uroot xxl_job \
  < "${SCRIPT_DIR}/bootstrap-business-jobs.sql"

"${compose[@]}" up -d xxl-job-admin
for _ in {1..60}; do
  if curl -fsS "http://127.0.0.1:${XXL_JOB_ADMIN_PORT:-8080}/xxl-job-admin/" >/dev/null 2>&1; then
    echo "XXL-JOB Admin 已就绪"
    echo "请在应用侧配置 XXL_JOB_EXECUTOR_ENABLED=true 与相同 XXL_JOB_ACCESS_TOKEN"
    exit 0
  fi
  sleep 2
done

echo "XXL-JOB Admin 健康检查超时" >&2
"${compose[@]}" logs --tail=100 xxl-job-admin >&2
exit 1
