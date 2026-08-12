-- Read-only post-migration verification. Run after selecting the target application database.
SET @schema_name = DATABASE();

SELECT required.table_name,
       IF(actual.TABLE_NAME IS NULL, 'MISSING', 'OK') AS verification_status
FROM (
  SELECT 'agent_tool_permission' AS table_name
  UNION ALL SELECT 'agent_tool_approval_request'
  UNION ALL SELECT 'agent_subagent_task'
  UNION ALL SELECT 'agent_parent_inbox'
  UNION ALL SELECT 'agent_parent_resume_request'
  UNION ALL SELECT 'agent_orchestration_outbox'
) required
LEFT JOIN information_schema.TABLES actual
  ON actual.TABLE_SCHEMA=@schema_name AND actual.TABLE_NAME=required.table_name
ORDER BY required.table_name;

SELECT required.table_name, required.column_name,
       IF(actual.COLUMN_NAME IS NULL, 'MISSING', 'OK') AS verification_status
FROM (
  SELECT 'agent_subagent_task' AS table_name, 'child_session_id' AS column_name
  UNION ALL SELECT 'agent_subagent_task', 'result_summary'
  UNION ALL SELECT 'agent_subagent_task', 'full_context'
  UNION ALL SELECT 'agent_subagent_task', 'summary_truncated'
  UNION ALL SELECT 'agent_subagent_task', 'fencing_token'
  UNION ALL SELECT 'agent_subagent_task', 'lease_expires_at'
  UNION ALL SELECT 'agent_subagent_task', 'callback_claimed_at'
  UNION ALL SELECT 'agent_parent_resume_request', 'inbox_cursor'
  UNION ALL SELECT 'agent_parent_resume_request', 'fencing_token'
  UNION ALL SELECT 'agent_orchestration_outbox', 'event_id'
  UNION ALL SELECT 'agent_tool_approval_request', 'function_call_id'
) required
LEFT JOIN information_schema.COLUMNS actual
  ON actual.TABLE_SCHEMA=@schema_name
 AND actual.TABLE_NAME=required.table_name
 AND actual.COLUMN_NAME=required.column_name
ORDER BY required.table_name, required.column_name;

SELECT required.table_name, required.index_name,
       IF(actual.INDEX_NAME IS NULL, 'MISSING', 'OK') AS verification_status
FROM (
  SELECT 'agent_subagent_task' AS table_name, 'uk_subagent_task_tenant_task' AS index_name
  UNION ALL SELECT 'agent_parent_inbox', 'uk_parent_inbox_task'
  UNION ALL SELECT 'agent_parent_resume_request', 'uk_parent_resume_run'
  UNION ALL SELECT 'agent_orchestration_outbox', 'uk_agent_outbox_event'
  UNION ALL SELECT 'agent_tool_permission', 'uk_agent_tool_permission'
  UNION ALL SELECT 'agent_tool_approval_request', 'uk_tool_approval_call'
) required
LEFT JOIN (
  SELECT DISTINCT TABLE_NAME, INDEX_NAME
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@schema_name
) actual
  ON actual.TABLE_NAME=required.table_name AND actual.INDEX_NAME=required.index_name
ORDER BY required.table_name, required.index_name;
