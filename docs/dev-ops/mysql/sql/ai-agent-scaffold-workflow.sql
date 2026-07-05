USE `ai_agent_scaffold`;

CREATE TABLE IF NOT EXISTS `agent_workflow` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '工作流拥有者用户ID',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/tenant_public',
  `workflow_id` VARCHAR(64) NOT NULL COMMENT '工作流业务ID',
  `workflow_name` VARCHAR(128) NOT NULL COMMENT '工作流名称',
  `description` VARCHAR(512) NULL COMMENT '工作流描述',
  `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '工作流状态：draft/published/disabled/archived',
  `default_model_code` VARCHAR(128) NOT NULL DEFAULT 'deepseek-v4-flash' COMMENT '默认模型编码',
  `current_version` INT NOT NULL DEFAULT 1 COMMENT '当前草稿版本',
  `published_version` INT NOT NULL DEFAULT 0 COMMENT '当前发布版本，0表示未发布',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_workflow_tenant_workflow` (`tenant_id`, `workflow_id`),
  KEY `idx_agent_workflow_owner` (`owner_user_id`, `create_time`),
  KEY `idx_agent_workflow_tenant_status` (`tenant_id`, `status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent_workflow';

CREATE TABLE IF NOT EXISTS `agent_workflow_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `workflow_id` VARCHAR(64) NOT NULL COMMENT '工作流业务ID',
  `version` INT NOT NULL COMMENT '版本号',
  `version_status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '版本状态：draft/published/archived',
  `default_model_code` VARCHAR(128) NOT NULL DEFAULT 'deepseek-v4-flash' COMMENT '默认模型编码',
  `graph_json` LONGTEXT NOT NULL COMMENT '画布图结构 JSON',
  `created_by` VARCHAR(64) NOT NULL COMMENT '创建者用户ID',
  `published_by` VARCHAR(64) NULL COMMENT '发布者用户ID',
  `published_time` DATETIME(3) NULL COMMENT '发布时间',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_workflow_version` (`tenant_id`, `workflow_id`, `version`),
  KEY `idx_agent_workflow_version_status` (`tenant_id`, `workflow_id`, `version_status`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent_workflow_version';
