-- 通用工作流事件游标历史回填。
-- 在 schema 脚本之后执行；只读 workflow 类 chat_run 及其历史事件，不创建智能运行扩展行。

INSERT INTO workflow_run_event_cursor
    (tenant_id, user_id, run_id, trace_id, next_sequence, revision)
SELECT run.tenant_id,
       run.user_id,
       run.run_id,
       run.trace_id,
       GREATEST(COALESCE(MAX(event.sequence) + 1, 1), COALESCE(MAX(intelligent.next_sequence), 1)),
       0
FROM chat_run run
LEFT JOIN workflow_run_event event
       ON event.tenant_id = run.tenant_id
      AND event.user_id = run.user_id
      AND event.run_id = run.run_id
      AND event.deleted = 0
LEFT JOIN intelligent_workflow_run intelligent
       ON intelligent.tenant_id = run.tenant_id
      AND intelligent.user_id = run.user_id
      AND intelligent.run_id = run.run_id
      AND intelligent.deleted = 0
WHERE run.source_type = 'workflow'
  AND run.trace_id IS NOT NULL
  AND run.trace_id <> ''
  AND run.tenant_id IS NOT NULL
  AND run.deleted = 0
GROUP BY run.tenant_id, run.user_id, run.run_id, run.trace_id
ON DUPLICATE KEY UPDATE
    user_id = VALUES(user_id),
    trace_id = VALUES(trace_id),
    next_sequence = GREATEST(workflow_run_event_cursor.next_sequence, VALUES(next_sequence)),
    revision = workflow_run_event_cursor.revision + 1,
    deleted = 0;

-- 历史 Run 若已经有终态事件，回填唯一终态槽位，禁止升级后的进程继续追加迟到节点事件。
UPDATE workflow_run_event_cursor cursor
INNER JOIN (
    SELECT tenant_id, run_id, MAX(sequence) AS terminal_sequence,
           SUBSTRING_INDEX(GROUP_CONCAT(event_type ORDER BY sequence DESC), ',', 1) AS terminal_event_type
    FROM workflow_run_event
    WHERE event_type IN ('WORKFLOW_COMPLETED', 'WORKFLOW_FAILED', 'WORKFLOW_CANCELLED') AND deleted = 0
    GROUP BY tenant_id, run_id
) terminal ON terminal.tenant_id = cursor.tenant_id AND terminal.run_id = cursor.run_id
SET cursor.terminal_event_type = terminal.terminal_event_type,
    cursor.terminal_sequence = terminal.terminal_sequence,
    cursor.next_sequence = GREATEST(cursor.next_sequence, terminal.terminal_sequence + 1),
    cursor.revision = cursor.revision + 1
WHERE cursor.deleted = 0 AND cursor.terminal_event_type IS NULL;
