#!/usr/bin/env bash
set -euo pipefail

: "${KAFKA_BOOTSTRAP_SERVERS:?must set KAFKA_BOOTSTRAP_SERVERS}"

topic_command="${KAFKA_TOPICS_COMMAND:-kafka-topics.sh}"
partitions="${AI_AGENT_TOPIC_PARTITIONS:-6}"
replication_factor="${AI_AGENT_TOPIC_REPLICATION_FACTOR:-3}"
apply="${APPLY:-false}"

if ! command -v "${topic_command}" >/dev/null 2>&1; then
  echo "Kafka topic command not found: ${topic_command}" >&2
  exit 1
fi

connection=(--bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}")
if [[ -n "${KAFKA_COMMAND_CONFIG:-}" ]]; then
  if [[ ! -f "${KAFKA_COMMAND_CONFIG}" ]]; then
    echo "KAFKA_COMMAND_CONFIG does not exist: ${KAFKA_COMMAND_CONFIG}" >&2
    exit 1
  fi
  connection+=(--command-config "${KAFKA_COMMAND_CONFIG}")
fi

result_topic="${AI_AGENT_RESULT_TOPIC:-agent.subagent.result.v1}"
topics=(
  "${AI_AGENT_TASK_TOPIC:-agent.subagent.task.v1}"
  "${result_topic}"
  "${AI_AGENT_CLEANUP_TOPIC:-agent.subagent.cleanup.v1}"
  "${AI_AGENT_RESUME_TOPIC:-agent.parent.resume.v1}"
  # @RetryableTopic 使用默认的 SUFFIX_WITH_INDEX_VALUE；单一重试 Topic 的实际名称为 -retry-0。
  "${result_topic}-retry-0"
  "${result_topic}-dlt"
)

for topic in "${topics[@]}"; do
  create=("${topic_command}" "${connection[@]}" --create --if-not-exists
    --topic "${topic}" --partitions "${partitions}" --replication-factor "${replication_factor}")
  if [[ "${apply}" != "true" ]]; then
    printf 'DRY-RUN:'
    printf ' %q' "${create[@]}"
    printf '\n'
    continue
  fi
  "${create[@]}"
  "${topic_command}" "${connection[@]}" --describe --topic "${topic}"
done

if [[ "${apply}" != "true" ]]; then
  echo "No topic was changed. Re-run with APPLY=true after reviewing partitions and replication factor."
fi
