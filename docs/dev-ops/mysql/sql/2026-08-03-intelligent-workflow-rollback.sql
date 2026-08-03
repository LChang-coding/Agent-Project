-- 非破坏性回滚：停止创建/调度新的智能工作流运行即可。
-- 本脚本故意不 DROP 表、不 DELETE 数据，保留历史运行、事件和审计证据供旧版本忽略。
UPDATE intelligent_workflow_run
SET status = 'PAUSED', revision = revision + 1
WHERE status IN ('CREATED', 'RUNNING', 'WAITING_RETRY');

UPDATE workflow_execution_task
SET status = 'PAUSED', revision = revision + 1
WHERE status IN ('PENDING', 'LEASED', 'RETRY_WAIT');
