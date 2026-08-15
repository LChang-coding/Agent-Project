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
  UNION ALL SELECT 'agent_parent_resume_request', 'parent_ready'
  UNION ALL SELECT 'agent_parent_resume_request', 'parent_draft'
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

-- WAIT_ALL 新列必须具备精确类型、空值约束和默认值；仅“存在”不足以证明可发布。
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT,
       CASE
         WHEN COLUMN_NAME='parent_ready' AND COLUMN_TYPE='tinyint unsigned'
              AND IS_NULLABLE='NO' AND COLUMN_DEFAULT='0' THEN 'OK'
         WHEN COLUMN_NAME='parent_draft' AND COLUMN_TYPE='mediumtext'
              AND IS_NULLABLE='YES' THEN 'OK'
         WHEN COLUMN_NAME='status' AND COLUMN_TYPE='varchar(24)'
              AND IS_NULLABLE='NO' AND COLUMN_DEFAULT='WAITING' THEN 'OK'
         ELSE 'INVALID'
       END AS verification_status
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='agent_parent_resume_request'
  AND COLUMN_NAME IN ('status','parent_ready','parent_draft')
ORDER BY ORDINAL_POSITION;

-- 任一结果非 0 都是发布阻断：双屏障状态不完整，或孤儿恢复仍会反复重试。
SELECT
  COALESCE(SUM(status IN ('PENDING','RUNNING','RETRYING') AND parent_ready<>1),0) AS active_without_parent_ready,
  COALESCE(SUM(status='PENDING' AND requested_version<=processed_version),0) AS pending_without_new_version,
  COALESCE(SUM(status='RUNNING' AND lease_expires_at IS NULL),0) AS running_without_lease,
  COALESCE(SUM(status IN ('WAITING','PENDING','RUNNING','RETRYING') AND session_missing=1),0) AS orphan_active_requests
FROM (
  SELECT r.*,
         IF(s.session_id IS NULL OR s.deleted<>0,1,0) AS session_missing
  FROM agent_parent_resume_request r
  LEFT JOIN chat_session s
    ON s.tenant_id COLLATE utf8mb4_unicode_ci=r.tenant_id COLLATE utf8mb4_unicode_ci
   AND s.session_id COLLATE utf8mb4_unicode_ci=r.parent_session_id COLLATE utf8mb4_unicode_ci
   AND s.user_id COLLATE utf8mb4_unicode_ci=r.user_id COLLATE utf8mb4_unicode_ci
  WHERE r.deleted=0
) checked;
