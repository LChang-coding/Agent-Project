-- 非破坏性回滚：回切旧版前，先把智能运行的序号推进到通用游标水位。
-- 不 DROP 表、不 DELETE 数据；静态工作流事件保留供再次前进部署后续传。

UPDATE intelligent_workflow_run intelligent
INNER JOIN workflow_run_event_cursor cursor
        ON cursor.tenant_id = intelligent.tenant_id
       AND cursor.user_id = intelligent.user_id
       AND cursor.run_id = intelligent.run_id
       AND cursor.deleted = 0
SET intelligent.next_sequence = GREATEST(intelligent.next_sequence, cursor.next_sequence),
    intelligent.revision = intelligent.revision + 1
WHERE intelligent.deleted = 0;
