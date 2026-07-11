ALTER TABLE chat_message
    ADD COLUMN estimated_token_count INT NULL COMMENT '上下文 token 预估值' AFTER content;

CREATE TABLE conversation_memory_snapshot (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id VARCHAR(64) NULL COMMENT '租户ID',
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    memory_version INT NOT NULL COMMENT '记忆版本',
    covered_to_sequence INT NOT NULL COMMENT '已覆盖消息序号',
    content JSON NOT NULL COMMENT '结构化长期记忆',
    estimated_token_count INT NOT NULL DEFAULT 0 COMMENT '摘要 token 预估值',
    policy_version VARCHAR(64) NOT NULL COMMENT '上下文策略版本',
    status VARCHAR(32) NOT NULL COMMENT '状态：active/superseded',
    trace_id VARCHAR(64) NULL COMMENT '链路ID',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_context_memory_version (tenant_id, user_id, session_id, memory_version),
    KEY idx_context_memory_active (tenant_id, user_id, session_id, status, covered_to_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话长期记忆快照';

CREATE TABLE context_compaction_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id VARCHAR(64) NOT NULL COMMENT '任务ID',
    task_key VARCHAR(256) NOT NULL COMMENT '幂等键',
    tenant_id VARCHAR(64) NULL COMMENT '租户ID',
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    from_sequence INT NOT NULL COMMENT '压缩起始序号',
    to_sequence INT NOT NULL COMMENT '压缩结束序号',
    expected_memory_version INT NOT NULL COMMENT '预期摘要版本',
    policy_version VARCHAR(64) NOT NULL COMMENT '上下文策略版本',
    status VARCHAR(32) NOT NULL COMMENT '状态：pending/processing/succeeded/retrying/dead',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '尝试次数',
    error_message VARCHAR(512) NULL COMMENT '错误摘要',
    trace_id VARCHAR(64) NULL COMMENT '链路ID',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_context_compaction_task (task_key),
    UNIQUE KEY uk_context_compaction_task_id (task_id),
    KEY idx_context_compaction_status (status, update_time),
    KEY idx_context_compaction_session (tenant_id, user_id, session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上下文压缩执行账本';
