-- RAG 调用方式快照与工作流路由意图。脚本可重复执行。
-- chat_session、chat_run 是前置基础表；列存在时跳过，不存在时补齐。
SET @schema_name = DATABASE();

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_session' AND COLUMN_NAME='rag_invocation_mode')=0,
              'ALTER TABLE chat_session ADD COLUMN rag_invocation_mode VARCHAR(32) NOT NULL DEFAULT ''AUTO_CONTEXT'' COMMENT ''RAG调用方式：AUTO_CONTEXT/AGENT_TOOL'' AFTER rag_mode',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE chat_session
SET rag_invocation_mode = 'AUTO_CONTEXT'
WHERE rag_invocation_mode IS NULL OR rag_invocation_mode = '';

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_run' AND COLUMN_NAME='rag_invocation_mode')=0,
              'ALTER TABLE chat_run ADD COLUMN rag_invocation_mode VARCHAR(32) NOT NULL DEFAULT ''AUTO_CONTEXT'' COMMENT ''运行RAG调用方式快照'' AFTER rag_mode',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE chat_run
SET rag_invocation_mode = 'AUTO_CONTEXT'
WHERE rag_invocation_mode IS NULL OR rag_invocation_mode = '';

CREATE TABLE IF NOT EXISTS workflow_route_intent (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(80) NOT NULL,
    node_execution_id VARCHAR(80) NOT NULL,
    workflow_id VARCHAR(80) NOT NULL,
    workflow_version INT NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    route_key VARCHAR(128) NOT NULL,
    normalized_route_key VARCHAR(128) NOT NULL,
    resolved_edge_id VARCHAR(128) NOT NULL,
    resolved_target_node_id VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    function_call_id VARCHAR(128) NOT NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    consumed_at DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wri_node_execution (tenant_id, run_id, node_execution_id),
    UNIQUE KEY uk_wri_function_call (tenant_id, function_call_id),
    KEY idx_wri_trace (trace_id),
    KEY idx_wri_run (tenant_id, run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作流路由意图';

-- IF NOT EXISTS 不会修复已存在表的缺失索引，按命名索引逐项补齐。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='workflow_route_intent' AND INDEX_NAME='uk_wri_node_execution')=0,
              'ALTER TABLE workflow_route_intent ADD UNIQUE KEY uk_wri_node_execution (tenant_id, run_id, node_execution_id)',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='workflow_route_intent' AND INDEX_NAME='uk_wri_function_call')=0,
              'ALTER TABLE workflow_route_intent ADD UNIQUE KEY uk_wri_function_call (tenant_id, function_call_id)',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='workflow_route_intent' AND INDEX_NAME='idx_wri_trace')=0,
              'ALTER TABLE workflow_route_intent ADD KEY idx_wri_trace (trace_id)',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='workflow_route_intent' AND INDEX_NAME='idx_wri_run')=0,
              'ALTER TABLE workflow_route_intent ADD KEY idx_wri_run (tenant_id, run_id, status)',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
