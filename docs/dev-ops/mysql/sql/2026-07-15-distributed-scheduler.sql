-- 分布式定时任务增量迁移。
-- 时间字段统一存 UTC；执行前请先备份三张 agent_schedule_* 表。

ALTER TABLE `agent_schedule_config`
  ADD COLUMN `task_type` VARCHAR(64) NOT NULL DEFAULT 'agent_prompt' COMMENT '任务类型' AFTER `agent_name`,
  ADD COLUMN `task_payload` LONGTEXT NULL COMMENT '白名单任务载荷 JSON' AFTER `task_type`,
  ADD COLUMN `run_as_role_code` VARCHAR(64) NULL COMMENT '固化执行角色' AFTER `task_payload`,
  ADD COLUMN `misfire_policy` VARCHAR(32) NOT NULL DEFAULT 'fire_once_now' COMMENT '错过策略' AFTER `status`,
  ADD COLUMN `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数' AFTER `misfire_policy`,
  ADD COLUMN `config_hash` CHAR(64) NULL COMMENT '规范化配置摘要' AFTER `max_retries`,
  ADD COLUMN `config_version` BIGINT NOT NULL DEFAULT 0 COMMENT '配置收敛版本' AFTER `config_hash`,
  ADD COLUMN `last_reconciled_at` DATETIME(3) NULL COMMENT '最近对账时间' AFTER `config_version`,
  ADD KEY `idx_schedule_config_reconcile` (`last_reconciled_at`, `id`);

ALTER TABLE `agent_schedule_task`
  ADD COLUMN `business_key` CHAR(64) NULL COMMENT '租户与配置稳定业务键' AFTER `task_id`,
  ADD COLUMN `config_hash` CHAR(64) NULL COMMENT '当前配置摘要' AFTER `business_key`,
  ADD COLUMN `config_version` BIGINT NOT NULL DEFAULT 0 COMMENT '运行态配置版本' AFTER `config_hash`,
  ADD COLUMN `cron_expr` VARCHAR(128) NULL COMMENT 'Cron 快照' AFTER `config_version`,
  ADD COLUMN `timezone` VARCHAR(64) NULL COMMENT '时区快照' AFTER `cron_expr`,
  ADD COLUMN `misfire_policy` VARCHAR(32) NOT NULL DEFAULT 'fire_once_now' COMMENT '错过策略快照' AFTER `timezone`,
  ADD COLUMN `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数快照' AFTER `misfire_policy`,
  ADD COLUMN `next_fire_time` DATETIME(3) NULL COMMENT '下一计划时间 UTC' AFTER `planned_time`,
  ADD COLUMN `last_planned_time` DATETIME(3) NULL COMMENT '上一计划时间 UTC' AFTER `next_fire_time`,
  ADD COLUMN `retry_at` DATETIME(3) NULL COMMENT '失败重试时间 UTC' AFTER `last_planned_time`,
  ADD COLUMN `lease_owner` VARCHAR(160) NULL COMMENT '租约持有者' AFTER `retry_count`,
  ADD COLUMN `lease_until` DATETIME(3) NULL COMMENT '租约截止时间 UTC' AFTER `lease_owner`,
  ADD COLUMN `fencing_token` BIGINT NOT NULL DEFAULT 0 COMMENT '单调栅栏令牌' AFTER `lease_until`,
  ADD COLUMN `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '行版本' AFTER `fencing_token`;

UPDATE `agent_schedule_task`
SET `business_key` = SHA2(CONCAT(IFNULL(`tenant_id`, ''), '|', `config_id`), 256),
    `next_fire_time` = `planned_time`,
    `status` = IF(`status` IN ('running'), 'retry', IF(`status` = 'canceled', 'disabled', 'ready'))
WHERE `business_key` IS NULL;

ALTER TABLE `agent_schedule_task`
  MODIFY COLUMN `business_key` CHAR(64) NOT NULL COMMENT '租户与配置稳定业务键',
  MODIFY COLUMN `next_fire_time` DATETIME(3) NOT NULL COMMENT '下一计划时间 UTC',
  ADD UNIQUE KEY `uk_schedule_task_config` (`config_id`),
  ADD UNIQUE KEY `uk_schedule_task_business` (`business_key`),
  ADD KEY `idx_schedule_task_due` (`status`, `retry_at`, `next_fire_time`, `lease_until`);

ALTER TABLE `agent_schedule_execution`
  ADD COLUMN `config_id` VARCHAR(64) NULL COMMENT '调度配置业务ID' AFTER `user_id`,
  ADD COLUMN `trigger_key` VARCHAR(180) NULL COMMENT '计划触发点幂等键' AFTER `execution_id`,
  ADD COLUMN `planned_time` DATETIME(3) NULL COMMENT '计划触发时间 UTC' AFTER `trace_id`,
  ADD COLUMN `attempt_no` INT NOT NULL DEFAULT 1 COMMENT '当前尝试次数' AFTER `planned_time`,
  ADD COLUMN `fencing_token` BIGINT NOT NULL DEFAULT 0 COMMENT '执行栅栏令牌' AFTER `attempt_no`,
  ADD COLUMN `lease_owner` VARCHAR(160) NULL COMMENT '执行租约持有者' AFTER `fencing_token`,
  ADD COLUMN `result_json` LONGTEXT NULL COMMENT '白名单执行结果 JSON' AFTER `error_message`;

UPDATE `agent_schedule_execution` execution
LEFT JOIN `agent_schedule_task` task ON task.`task_id` = execution.`task_id`
SET execution.`config_id` = task.`config_id`,
    execution.`trigger_key` = CONCAT('legacy:', execution.`execution_id`),
    execution.`planned_time` = COALESCE(task.`planned_time`, execution.`start_time`, execution.`create_time`)
WHERE execution.`trigger_key` IS NULL;

ALTER TABLE `agent_schedule_execution`
  MODIFY COLUMN `config_id` VARCHAR(64) NOT NULL COMMENT '调度配置业务ID',
  MODIFY COLUMN `trigger_key` VARCHAR(180) NOT NULL COMMENT '计划触发点幂等键',
  MODIFY COLUMN `planned_time` DATETIME(3) NOT NULL COMMENT '计划触发时间 UTC',
  ADD UNIQUE KEY `uk_schedule_execution_trigger` (`trigger_key`),
  ADD KEY `idx_schedule_execution_config` (`config_id`, `planned_time`);
