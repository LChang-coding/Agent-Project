-- 通用工作流事件序号游标增量迁移。
-- 只扩展表结构；历史数据回填由同日 backfill 脚本独立执行。

CREATE TABLE IF NOT EXISTS workflow_run_event_cursor (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户业务ID',
    user_id VARCHAR(64) NOT NULL COMMENT '运行所属用户',
    run_id VARCHAR(80) NOT NULL COMMENT 'chat_run 运行ID',
    trace_id VARCHAR(64) NOT NULL COMMENT '运行根链路ID',
    next_sequence BIGINT NOT NULL DEFAULT 1 COMMENT '下一个可分配事件序号',
    terminal_event_type VARCHAR(64) NULL COMMENT '唯一终态事件类型',
    terminal_sequence BIGINT NULL COMMENT '唯一终态事件序号',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT '序号分配乐观锁版本',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wrec_run (tenant_id, run_id),
    KEY idx_wrec_owner (tenant_id, user_id, run_id, deleted),
    KEY idx_wrec_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用工作流事件序号游标';

SET @schema_name = DATABASE();
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name
               AND TABLE_NAME='workflow_run_event_cursor' AND COLUMN_NAME='terminal_event_type')=0,
              'ALTER TABLE workflow_run_event_cursor ADD COLUMN terminal_event_type VARCHAR(64) NULL COMMENT ''唯一终态事件类型'' AFTER next_sequence', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name
               AND TABLE_NAME='workflow_run_event_cursor' AND COLUMN_NAME='terminal_sequence')=0,
              'ALTER TABLE workflow_run_event_cursor ADD COLUMN terminal_sequence BIGINT NULL COMMENT ''唯一终态事件序号'' AFTER terminal_event_type', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
