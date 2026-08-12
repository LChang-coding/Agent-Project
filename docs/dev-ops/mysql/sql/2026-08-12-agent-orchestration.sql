-- 分布式 Multi-Agent 编排：任务账本 + Transactional Outbox。
-- 在目标环境执行前先核对 DATABASE() 并保留可恢复备份。

CREATE TABLE IF NOT EXISTS `agent_tool_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` VARCHAR(64) NOT NULL,
  `agent_id` VARCHAR(128) NOT NULL,
  `tool_code` VARCHAR(128) NOT NULL,
  `mode` VARCHAR(32) NOT NULL,
  `timeout_seconds` INT UNSIGNED NOT NULL DEFAULT 600,
  `timeout_decision` VARCHAR(32) NOT NULL DEFAULT 'REJECT',
  `suggestions_json` JSON NOT NULL,
  `revision` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by` VARCHAR(64) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tool_permission` (`tenant_id`,`agent_id`,`tool_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主Agent工具权限策略';

CREATE TABLE IF NOT EXISTS `agent_tool_approval_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `approval_id` VARCHAR(64) NOT NULL, `tenant_id` VARCHAR(64) NOT NULL, `user_id` VARCHAR(64) NOT NULL,
  `parent_run_id` VARCHAR(64) NOT NULL, `source_run_id` VARCHAR(64) NOT NULL,
  `parent_session_id` VARCHAR(64) NOT NULL, `parent_agent_id` VARCHAR(128) NOT NULL,
  `function_call_id` VARCHAR(128) NOT NULL, `tool_code` VARCHAR(128) NOT NULL,
  `requested_input_json` JSON NOT NULL, `amended_input_json` JSON DEFAULT NULL,
  `allowed_subagent_ids_json` JSON NOT NULL, `suggestions_json` JSON NOT NULL,
  `status` VARCHAR(24) NOT NULL, `timeout_decision` VARCHAR(24) NOT NULL, `expires_at` DATETIME(3) NOT NULL,
  `decision` VARCHAR(32) DEFAULT NULL, `comment` VARCHAR(500) DEFAULT NULL, `decided_by` VARCHAR(64) DEFAULT NULL,
  `decided_at` DATETIME(3) DEFAULT NULL,
  `revision` BIGINT UNSIGNED NOT NULL DEFAULT 0, `trace_id` VARCHAR(64) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_tool_approval_id` (`tenant_id`,`approval_id`),
  UNIQUE KEY `uk_tool_approval_call` (`tenant_id`,`source_run_id`,`function_call_id`),
  KEY `idx_tool_approval_stream` (`tenant_id`,`user_id`,`id`),
  KEY `idx_tool_approval_due` (`status`,`expires_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台工具人工审批请求';

CREATE TABLE IF NOT EXISTS `agent_subagent_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` VARCHAR(64) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `parent_run_id` VARCHAR(64) NOT NULL,
  `parent_session_id` VARCHAR(64) NOT NULL,
  `parent_agent_id` VARCHAR(128) NOT NULL,
  `task_id` VARCHAR(64) NOT NULL,
  `child_agent_id` VARCHAR(128) NOT NULL,
  `child_session_id` VARCHAR(128) DEFAULT NULL,
  `instruction` MEDIUMTEXT NOT NULL,
  `function_call_id` VARCHAR(128) NOT NULL,
  `trace_id` VARCHAR(64) DEFAULT NULL,
  `status` VARCHAR(24) NOT NULL,
  `attempt` INT UNSIGNED NOT NULL DEFAULT 0,
  `fencing_token` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `lease_expires_at` DATETIME(3) DEFAULT NULL,
  `result_text` MEDIUMTEXT DEFAULT NULL,
  `result_summary` TEXT DEFAULT NULL,
  `full_context` MEDIUMTEXT DEFAULT NULL,
  `summary_truncated` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `error_code` VARCHAR(128) DEFAULT NULL,
  `callback_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `callback_owner` VARCHAR(128) DEFAULT NULL,
  `callback_claimed_at` DATETIME(3) DEFAULT NULL,
  `callback_attempt` INT UNSIGNED NOT NULL DEFAULT 0,
  `callback_last_error` VARCHAR(1000) DEFAULT NULL,
  `callback_delivered_at` DATETIME(3) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `completed_at` DATETIME(3) DEFAULT NULL,
  `acknowledged_at` DATETIME(3) DEFAULT NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subagent_task_tenant_task` (`tenant_id`,`task_id`),
  KEY `idx_subagent_function_replay` (`tenant_id`,`parent_run_id`,`function_call_id`,`id`),
  KEY `idx_subagent_parent_inbox` (`tenant_id`,`parent_run_id`,`status`,`id`),
  KEY `idx_subagent_lease_recovery` (`status`,`lease_expires_at`,`id`),
  KEY `idx_subagent_callback` (`callback_status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='临时子Agent任务与回调账本';

CREATE TABLE IF NOT EXISTS `agent_parent_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` VARCHAR(64) NOT NULL,
  `parent_run_id` VARCHAR(64) NOT NULL,
  `task_id` VARCHAR(64) NOT NULL,
  `child_agent_id` VARCHAR(128) NOT NULL,
  `result_summary` TEXT DEFAULT NULL,
  `task_status` VARCHAR(24) NOT NULL,
  `consumed_at` DATETIME(3) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_parent_inbox_task` (`tenant_id`,`parent_run_id`,`task_id`),
  KEY `idx_parent_inbox_cursor` (`tenant_id`,`parent_run_id`,`id`,`consumed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主Agent结果收件箱';

CREATE TABLE IF NOT EXISTS `agent_parent_resume_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` VARCHAR(64) NOT NULL,
  `user_id` VARCHAR(64) NOT NULL,
  `parent_run_id` VARCHAR(64) NOT NULL,
  `parent_session_id` VARCHAR(64) NOT NULL,
  `parent_agent_id` VARCHAR(128) NOT NULL,
  `trace_id` VARCHAR(64) DEFAULT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `requested_version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `processed_version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `inbox_cursor` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `fencing_token` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `lease_expires_at` DATETIME(3) DEFAULT NULL,
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `next_attempt_at` DATETIME(3) NOT NULL,
  `recovery_notified_at` DATETIME(3) DEFAULT NULL,
  `last_error` VARCHAR(1000) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_parent_resume_run` (`tenant_id`,`parent_run_id`),
  KEY `idx_parent_resume_due` (`status`,`next_attempt_at`,`lease_expires_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主Agent恢复请求与单飞租约';

CREATE TABLE IF NOT EXISTS `agent_orchestration_outbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` VARCHAR(64) NOT NULL,
  `event_id` VARCHAR(64) NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `aggregate_id` VARCHAR(64) NOT NULL,
  `partition_key` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `max_attempts` INT UNSIGNED NOT NULL DEFAULT 12,
  `next_attempt_at` DATETIME(3) NOT NULL,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `fencing_token` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `lease_expires_at` DATETIME(3) DEFAULT NULL,
  `last_error` VARCHAR(1000) DEFAULT NULL,
  `published_at` DATETIME(3) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_outbox_event` (`tenant_id`,`event_id`),
  KEY `idx_agent_outbox_due` (`status`,`next_attempt_at`,`id`),
  KEY `idx_agent_outbox_lease` (`status`,`lease_expires_at`,`id`),
  KEY `idx_agent_outbox_aggregate` (`tenant_id`,`aggregate_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent编排Transactional Outbox';

-- 兼容早期已执行过本迁移的环境：CREATE TABLE IF NOT EXISTS 不会为旧表补列。
SET @schema_name = DATABASE();

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='agent_subagent_task' AND COLUMN_NAME='child_session_id') = 0,
  'ALTER TABLE `agent_subagent_task` ADD COLUMN `child_session_id` VARCHAR(128) DEFAULT NULL AFTER `child_agent_id`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='agent_subagent_task' AND COLUMN_NAME='result_summary') = 0,
  'ALTER TABLE `agent_subagent_task` ADD COLUMN `result_summary` TEXT DEFAULT NULL AFTER `result_text`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='agent_subagent_task' AND COLUMN_NAME='full_context') = 0,
  'ALTER TABLE `agent_subagent_task` ADD COLUMN `full_context` MEDIUMTEXT DEFAULT NULL AFTER `result_summary`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='agent_subagent_task' AND COLUMN_NAME='summary_truncated') = 0,
  'ALTER TABLE `agent_subagent_task` ADD COLUMN `summary_truncated` TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER `full_context`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
