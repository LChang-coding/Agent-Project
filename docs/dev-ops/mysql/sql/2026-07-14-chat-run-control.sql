CREATE TABLE chat_run (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    run_id VARCHAR(64) NOT NULL COMMENT '运行ID',
    turn_id VARCHAR(64) NOT NULL COMMENT '用户轮次ID',
    tenant_id VARCHAR(64) NULL COMMENT '租户ID',
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    source_type VARCHAR(32) NOT NULL COMMENT '来源：agent/workflow',
    source_id VARCHAR(64) NOT NULL COMMENT 'Agent 或工作流ID',
    status VARCHAR(32) NOT NULL COMMENT '运行状态',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    base_context_revision BIGINT NOT NULL DEFAULT 0 COMMENT '运行创建时上下文版本',
    current_context_revision BIGINT NOT NULL DEFAULT 0 COMMENT '当前上下文版本',
    predecessor_run_id VARCHAR(64) NULL COMMENT '前驱运行ID',
    successor_run_id VARCHAR(64) NULL COMMENT '后继运行ID',
    user_message_id VARCHAR(64) NULL COMMENT '本轮用户消息ID',
    steer_instruction TEXT NULL COMMENT '引导指令',
    terminal_reason VARCHAR(256) NULL COMMENT '终态原因',
    cancel_requested_at DATETIME(3) NULL COMMENT '取消请求时间',
    started_at DATETIME(3) NULL COMMENT '开始时间',
    finished_at DATETIME(3) NULL COMMENT '结束时间',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_run_id (run_id),
    KEY idx_chat_run_session (tenant_id, user_id, session_id, status, create_time),
    KEY idx_chat_run_predecessor (predecessor_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话运行状态';

ALTER TABLE chat_session
    ADD COLUMN context_revision BIGINT NOT NULL DEFAULT 0 COMMENT '有效上下文版本' AFTER last_message_time;

ALTER TABLE chat_message
    ADD COLUMN run_id VARCHAR(64) NULL COMMENT '所属运行ID' AFTER message_id,
    ADD COLUMN validity_status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '有效性：active/invalidated/superseded' AFTER run_id,
    ADD COLUMN invalid_reason VARCHAR(256) NULL COMMENT '失效原因' AFTER validity_status,
    ADD COLUMN invalidated_at DATETIME(3) NULL COMMENT '失效时间' AFTER invalid_reason,
    ADD KEY idx_chat_message_run (tenant_id, user_id, session_id, run_id, validity_status),
    ADD KEY idx_chat_message_context (tenant_id, user_id, session_id, validity_status, sequence_no);

ALTER TABLE context_compaction_task
    ADD COLUMN run_id VARCHAR(64) NULL COMMENT '触发压缩的运行ID' AFTER session_id,
    ADD COLUMN base_context_revision BIGINT NOT NULL DEFAULT 0 COMMENT '压缩基准上下文版本' AFTER expected_memory_version,
    ADD COLUMN coverage_hash VARCHAR(64) NULL COMMENT '覆盖有效消息哈希' AFTER base_context_revision,
    ADD COLUMN lease_owner VARCHAR(128) NULL COMMENT '处理租约持有者' AFTER attempt_count,
    ADD COLUMN lease_until DATETIME(3) NULL COMMENT '处理租约截止时间' AFTER lease_owner,
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '压缩执行围栏版本' AFTER lease_until,
    ADD KEY idx_context_compaction_run (tenant_id, user_id, session_id, run_id, status);

ALTER TABLE conversation_memory_snapshot
    ADD COLUMN base_context_revision BIGINT NOT NULL DEFAULT 0 COMMENT '快照基准上下文版本' AFTER memory_version,
    ADD COLUMN coverage_hash VARCHAR(64) NULL COMMENT '覆盖有效消息哈希' AFTER covered_to_sequence,
    ADD COLUMN parent_memory_version INT NULL COMMENT '父快照版本' AFTER coverage_hash;

ALTER TABLE tool_call_log
    ADD COLUMN run_id VARCHAR(64) NULL COMMENT '所属运行ID' AFTER session_id,
    ADD COLUMN function_call_id VARCHAR(128) NULL COMMENT 'ADK 工具调用ID' AFTER invocation_id,
    ADD COLUMN idempotency_key VARCHAR(256) NULL COMMENT '工具幂等键' AFTER function_call_id,
    ADD COLUMN started_at DATETIME(3) NULL COMMENT '外部调用开始时间' AFTER status,
    ADD UNIQUE KEY uk_tool_call_idempotency (idempotency_key),
    ADD KEY idx_tool_call_run (tenant_id, user_id, session_id, run_id, status);
