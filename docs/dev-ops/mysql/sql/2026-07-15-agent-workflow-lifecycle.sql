-- Agent 租户覆盖与工作流软删除审计增量迁移（MySQL 8，可重复执行）。
CREATE TABLE IF NOT EXISTS `agent_tenant_override` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'active',
  `reason` VARCHAR(256) NULL,
  `updated_by` VARCHAR(64) NOT NULL,
  `revision` BIGINT NOT NULL DEFAULT 0,
  `disabled_at` DATETIME(3) NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tenant_override` (`tenant_id`, `agent_id`),
  KEY `idx_agent_override_status` (`tenant_id`, `status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户静态 Agent 状态覆盖';

SET @schema_name = DATABASE();
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name
               AND TABLE_NAME='agent_workflow' AND COLUMN_NAME='deleted_by')=0,
              'ALTER TABLE agent_workflow ADD COLUMN deleted_by VARCHAR(64) NULL COMMENT ''删除操作用户'' AFTER published_version', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name
               AND TABLE_NAME='agent_workflow' AND COLUMN_NAME='deleted_at')=0,
              'ALTER TABLE agent_workflow ADD COLUMN deleted_at DATETIME(3) NULL COMMENT ''删除时间'' AFTER deleted_by', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
