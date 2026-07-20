-- RAG知识库可恢复级联删除任务账本；MySQL 8.0+
CREATE TABLE IF NOT EXISTS `rag_knowledge_base_delete_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` VARCHAR(64) NOT NULL COMMENT '删除任务业务ID',
  `task_key` CHAR(64) NOT NULL COMMENT 'tenant+kb删除幂等键SHA-256',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库业务ID',
  `requested_by_user_id` VARCHAR(64) NOT NULL COMMENT '发起删除的管理员用户ID',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/waiting/retrying/completed/failed/dead',
  `checkpoint` JSON NOT NULL COMMENT '阶段和文档计数，不含正文与凭据',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '已领取次数',
  `max_attempts` INT NOT NULL DEFAULT 5 COMMENT '最大领取次数',
  `next_retry_at` DATETIME(3) NULL COMMENT '下一次重试时间UTC',
  `lease_owner` VARCHAR(160) NULL COMMENT '协调器租约持有者',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止UTC',
  `heartbeat_at` DATETIME(3) NULL COMMENT '最近续租UTC',
  `fencing_token` BIGINT NOT NULL DEFAULT 0 COMMENT '单调栅栏令牌',
  `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '行乐观锁版本',
  `error_code` VARCHAR(64) NULL COMMENT '稳定错误码',
  `error_message` VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  `started_at` DATETIME(3) NULL COMMENT '首次开始UTC',
  `finished_at` DATETIME(3) NULL COMMENT '最终结束UTC',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_kb_delete_task_id` (`tenant_id`, `task_id`),
  UNIQUE KEY `uk_rag_kb_delete_task_kb` (`tenant_id`, `kb_id`),
  UNIQUE KEY `uk_rag_kb_delete_task_key` (`tenant_id`, `task_key`),
  KEY `idx_rag_kb_delete_due` (`status`, `next_retry_at`, `lease_until`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG知识库级联删除任务账本';

DELIMITER $$
DROP PROCEDURE IF EXISTS `sp_rag_kb_delete_add_index_20260720`$$
CREATE PROCEDURE `sp_rag_kb_delete_add_index_20260720`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'rag_agent_binding'
      AND index_name = 'idx_rag_binding_tenant_kb_status'
  ) THEN
    ALTER TABLE `rag_agent_binding`
      ADD KEY `idx_rag_binding_tenant_kb_status`
        (`tenant_id`, `kb_id`, `status`, `deleted`, `id`);
  END IF;
END$$
CALL `sp_rag_kb_delete_add_index_20260720`()$$
DROP PROCEDURE IF EXISTS `sp_rag_kb_delete_add_index_20260720`$$
DELIMITER ;
