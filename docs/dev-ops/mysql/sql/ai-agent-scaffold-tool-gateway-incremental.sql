USE ai_agent_scaffold;

SET @schema_name = DATABASE();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'skill_definition' AND column_name = 'current_version') = 0,
  'ALTER TABLE skill_definition ADD COLUMN current_version VARCHAR(64) NOT NULL DEFAULT ''1.0.0'' COMMENT ''当前草稿版本号'' AFTER version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'skill_definition' AND column_name = 'published_version') = 0,
  'ALTER TABLE skill_definition ADD COLUMN published_version VARCHAR(64) NULL COMMENT ''当前发布版本号'' AFTER current_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'skill_definition' AND column_name = 'active_version_id') = 0,
  'ALTER TABLE skill_definition ADD COLUMN active_version_id VARCHAR(64) NULL COMMENT ''当前生效版本业务ID'' AFTER published_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `skill_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '版本发布用户ID',
  `skill_id` VARCHAR(64) NOT NULL COMMENT 'Skill 业务ID',
  `version_id` VARCHAR(64) NOT NULL COMMENT 'Skill 版本业务ID',
  `version` VARCHAR(64) NOT NULL COMMENT '版本号',
  `asset_id` VARCHAR(64) NOT NULL COMMENT '关联 artifact_asset 业务ID',
  `bucket` VARCHAR(128) NOT NULL COMMENT 'Skill 包对象存储桶',
  `object_key` VARCHAR(512) NOT NULL COMMENT 'Skill 包对象 Key',
  `file_name` VARCHAR(255) NULL COMMENT '原始文件名',
  `sha256` VARCHAR(128) NOT NULL COMMENT '文件 SHA-256 摘要',
  `size_bytes` BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，单位字节',
  `manifest_json` JSON NULL COMMENT 'SKILL.md front matter 或解析结果',
  `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '版本状态：draft/active/disabled/archived',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_version_id` (`version_id`),
  UNIQUE KEY `uk_skill_version` (`skill_id`, `version`),
  KEY `idx_skill_version_skill` (`tenant_id`, `skill_id`, `status`),
  KEY `idx_skill_version_asset` (`asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='skill_version';

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'mcp_server_config' AND column_name = 'current_version') = 0,
  'ALTER TABLE mcp_server_config ADD COLUMN current_version VARCHAR(64) NOT NULL DEFAULT ''1.0.0'' COMMENT ''当前草稿版本号'' AFTER description', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'mcp_server_config' AND column_name = 'published_version') = 0,
  'ALTER TABLE mcp_server_config ADD COLUMN published_version VARCHAR(64) NULL COMMENT ''当前发布版本号'' AFTER current_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'mcp_server_config' AND column_name = 'active_version_id') = 0,
  'ALTER TABLE mcp_server_config ADD COLUMN active_version_id VARCHAR(64) NULL COMMENT ''当前生效版本业务ID'' AFTER published_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'mcp_server_config' AND column_name = 'test_status') = 0,
  'ALTER TABLE mcp_server_config ADD COLUMN test_status VARCHAR(32) NOT NULL DEFAULT ''untested'' COMMENT ''测试状态：untested/success/failed'' AFTER active_version_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'mcp_server_config' AND column_name = 'test_message') = 0,
  'ALTER TABLE mcp_server_config ADD COLUMN test_message VARCHAR(512) NULL COMMENT ''最近一次测试结果说明'' AFTER test_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'mcp_server_config' AND column_name = 'last_test_time') = 0,
  'ALTER TABLE mcp_server_config ADD COLUMN last_test_time DATETIME(3) NULL COMMENT ''最近一次测试时间'' AFTER test_message', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `mcp_config_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '版本发布用户ID',
  `mcp_id` VARCHAR(64) NOT NULL COMMENT 'MCP 配置业务ID',
  `version_id` VARCHAR(64) NOT NULL COMMENT 'MCP 版本业务ID',
  `version` VARCHAR(64) NOT NULL COMMENT '版本号',
  `transport_type` VARCHAR(32) NOT NULL COMMENT '传输类型：sse/http/stdio/local',
  `endpoint` VARCHAR(512) NULL COMMENT '远程 MCP 地址',
  `command` VARCHAR(512) NULL COMMENT 'stdio 启动命令',
  `args` JSON NULL COMMENT 'stdio 启动参数',
  `env` JSON NULL COMMENT '运行环境变量或 user_secret 引用',
  `tool_schema_json` JSON NULL COMMENT '最近一次测试得到的工具 Schema',
  `test_status` VARCHAR(32) NOT NULL DEFAULT 'untested' COMMENT '测试状态：untested/success/failed',
  `test_message` VARCHAR(512) NULL COMMENT '测试结果说明',
  `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '版本状态：draft/active/disabled/archived',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_version_id` (`version_id`),
  UNIQUE KEY `uk_mcp_version` (`mcp_id`, `version`),
  KEY `idx_mcp_version_mcp` (`tenant_id`, `mcp_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='mcp_config_version';

CREATE TABLE IF NOT EXISTS `tool_call_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `user_id` VARCHAR(64) NOT NULL COMMENT '调用用户ID',
  `session_id` VARCHAR(64) NULL COMMENT '会话业务ID',
  `workflow_id` VARCHAR(64) NULL COMMENT '工作流业务ID',
  `tool_type` VARCHAR(32) NOT NULL COMMENT '工具类型：skill/mcp',
  `tool_id` VARCHAR(64) NOT NULL COMMENT '工具业务ID',
  `tool_name` VARCHAR(128) NOT NULL COMMENT '工具名称',
  `version` VARCHAR(64) NULL COMMENT '调用时工具版本号',
  `invocation_id` VARCHAR(128) NULL COMMENT 'ADK 调用ID',
  `trace_id` VARCHAR(64) NULL COMMENT '链路ID',
  `input_json` JSON NULL COMMENT '工具入参',
  `output_json` JSON NULL COMMENT '工具出参',
  `status` VARCHAR(32) NOT NULL COMMENT '调用状态：started/success/failed/timeout',
  `error_type` VARCHAR(128) NULL COMMENT '错误类型',
  `error_message` VARCHAR(1024) NULL COMMENT '错误信息',
  `cost_ms` BIGINT NULL COMMENT '调用耗时毫秒',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_tool_call_session` (`session_id`, `create_time`),
  KEY `idx_tool_call_user` (`user_id`, `create_time`),
  KEY `idx_tool_call_tool` (`tenant_id`, `tool_type`, `tool_id`, `create_time`),
  KEY `idx_tool_call_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='tool_call_log';
