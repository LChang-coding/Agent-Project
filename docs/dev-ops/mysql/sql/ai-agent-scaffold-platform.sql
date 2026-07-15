CREATE DATABASE IF NOT EXISTS `ai_agent_scaffold` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `ai_agent_scaffold`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `agent_tenant_override`;
DROP TABLE IF EXISTS `agent_schedule_execution`;
DROP TABLE IF EXISTS `agent_schedule_task`;
DROP TABLE IF EXISTS `agent_schedule_config`;
DROP TABLE IF EXISTS `agent_workflow_version`;
DROP TABLE IF EXISTS `agent_workflow`;
DROP TABLE IF EXISTS `mcp_server_config`;
DROP TABLE IF EXISTS `mcp_config_version`;
DROP TABLE IF EXISTS `tool_call_log`;
DROP TABLE IF EXISTS `skill_version`;
DROP TABLE IF EXISTS `skill_definition`;
DROP TABLE IF EXISTS `rag_chunk`;
DROP TABLE IF EXISTS `rag_document`;
DROP TABLE IF EXISTS `rag_knowledge_base`;
DROP TABLE IF EXISTS `artifact_asset`;
DROP TABLE IF EXISTS `model_usage`;
DROP TABLE IF EXISTS `chat_message`;
DROP TABLE IF EXISTS `chat_session`;
DROP TABLE IF EXISTS `user_secret`;
DROP TABLE IF EXISTS `tenant_user`;
DROP TABLE IF EXISTS `user_account`;
DROP TABLE IF EXISTS `tenant`;

CREATE TABLE `tenant` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `tenant_name` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '租户名称',
  `tenant_code` VARCHAR(64) NULL COMMENT '租户编码',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '租户状态：active/disabled',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_id` (`tenant_id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`),
  KEY `idx_tenant_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='tenant';

CREATE TABLE `user_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户业务ID',
  `username` VARCHAR(128) NULL COMMENT '用户名',
  `nickname` VARCHAR(128) NULL COMMENT '昵称',
  `email` VARCHAR(255) NULL COMMENT '邮箱',
  `phone` VARCHAR(32) NULL COMMENT '手机号',
  `avatar` VARCHAR(512) NULL COMMENT '头像地址',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '用户状态：active/disabled',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='user_account';

CREATE TABLE `tenant_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户业务ID',
  `role_code` VARCHAR(64) NOT NULL DEFAULT 'member' COMMENT '租户角色：owner/admin/developer/member',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '关系状态：active/disabled',
  `joined_time` DATETIME(3) NULL COMMENT '加入租户时间',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_user` (`tenant_id`, `user_id`),
  KEY `idx_tenant_user_tenant_role` (`tenant_id`, `role_code`),
  KEY `idx_tenant_user_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='tenant_user';

CREATE TABLE `user_secret` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `user_id` VARCHAR(64) NOT NULL COMMENT '用户业务ID',
  `secret_type` VARCHAR(64) NOT NULL COMMENT '凭证类型：password/api_key/oauth',
  `secret_value_hash` VARCHAR(255) NOT NULL COMMENT '凭证密文或哈希值',
  `salt` VARCHAR(128) NULL COMMENT '密码盐值',
  `expire_time` DATETIME(3) NULL COMMENT '凭证过期时间',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '凭证状态：active/disabled/expired',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_secret_type` (`user_id`, `secret_type`),
  KEY `idx_user_secret_tenant` (`tenant_id`),
  KEY `idx_user_secret_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='user_secret';

CREATE TABLE `chat_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `user_id` VARCHAR(64) NOT NULL COMMENT '会话归属用户ID',
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话业务ID',
  `agent_id` VARCHAR(64) NULL COMMENT 'Agent ID',
  `agent_name` VARCHAR(128) NULL COMMENT 'Agent 名称',
  `source_type` VARCHAR(32) NOT NULL DEFAULT 'agent' COMMENT '运行目标类型：agent/workflow',
  `workflow_version` INT NULL COMMENT '工作流实际运行版本',
  `model_code` VARCHAR(128) NULL COMMENT '工作流实际运行模型编码',
  `task_type` VARCHAR(64) NOT NULL DEFAULT 'agent_prompt' COMMENT '任务类型',
  `task_payload` LONGTEXT NULL COMMENT '白名单任务载荷 JSON',
  `run_as_role_code` VARCHAR(64) NULL COMMENT '固化执行角色',
  `app_name` VARCHAR(128) NULL COMMENT '应用名称',
  `title` VARCHAR(255) NULL COMMENT '会话标题',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '会话状态：active/archived/deleted',
  `last_message_time` DATETIME(3) NULL COMMENT '最后消息时间',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_session_id` (`session_id`),
  KEY `idx_chat_session_user` (`user_id`, `last_message_time`),
  KEY `idx_chat_session_tenant` (`tenant_id`, `last_message_time`),
  KEY `idx_chat_session_agent` (`agent_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='chat_session';

CREATE TABLE `chat_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `user_id` VARCHAR(64) NOT NULL COMMENT '消息归属用户ID',
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话业务ID',
  `message_id` VARCHAR(64) NOT NULL COMMENT '消息业务ID',
  `role` VARCHAR(32) NOT NULL COMMENT '消息角色：user/assistant/tool/system',
  `content_type` VARCHAR(32) NOT NULL DEFAULT 'text' COMMENT '内容类型：text/json/markdown/file_ref',
  `content` LONGTEXT NULL COMMENT '消息内容',
  `sequence_no` INT NOT NULL DEFAULT 0 COMMENT '会话内消息序号',
  `parent_message_id` VARCHAR(64) NULL COMMENT '父消息ID',
  `trace_id` VARCHAR(64) NULL COMMENT '链路ID',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_message_id` (`message_id`),
  UNIQUE KEY `uk_chat_message_seq` (`session_id`, `sequence_no`),
  KEY `idx_chat_message_session` (`session_id`, `id`),
  KEY `idx_chat_message_user` (`user_id`, `id`),
  KEY `idx_chat_message_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='chat_message';

CREATE TABLE `model_usage` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `user_id` VARCHAR(64) NOT NULL COMMENT '用量归属用户ID',
  `session_id` VARCHAR(64) NULL COMMENT '会话业务ID',
  `run_id` VARCHAR(64) NULL COMMENT '业务运行ID',
  `call_id` VARCHAR(96) NULL COMMENT '单次模型调用幂等ID',
  `message_id` VARCHAR(64) NULL COMMENT '消息业务ID',
  `agent_id` VARCHAR(64) NULL COMMENT 'Agent ID',
  `agent_name` VARCHAR(128) NULL COMMENT 'Agent 名称',
  `app_name` VARCHAR(128) NULL COMMENT '应用名称',
  `invocation_id` VARCHAR(128) NOT NULL COMMENT '模型调用ID',
  `provider` VARCHAR(64) NULL COMMENT '模型供应商',
  `model_version` VARCHAR(128) NOT NULL COMMENT '模型版本',
  `usage_type` VARCHAR(32) NOT NULL DEFAULT 'chat' COMMENT '用量类型',
  `call_status` VARCHAR(32) NOT NULL DEFAULT 'success' COMMENT '调用终态',
  `finish_reason` VARCHAR(128) NULL COMMENT '模型结束原因',
  `prompt_tokens` INT NULL COMMENT '输入 token 数',
  `candidate_tokens` INT NULL COMMENT '输出 token 数',
  `total_tokens` INT NOT NULL DEFAULT 0 COMMENT '总 token 数',
  `thoughts_tokens` INT NULL COMMENT '思考 token 数',
  `tool_use_prompt_tokens` INT NULL COMMENT '工具调用提示 token 数',
  `trace_id` VARCHAR(64) NULL COMMENT '链路ID',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_usage_call` (`call_id`),
  KEY `idx_model_usage_user_time` (`user_id`, `create_time`),
  KEY `idx_model_usage_session` (`session_id`, `create_time`),
  KEY `idx_model_usage_run` (`tenant_id`, `user_id`, `session_id`, `run_id`, `create_time`),
  KEY `idx_model_usage_model` (`model_version`, `create_time`),
  KEY `idx_model_usage_invocation` (`invocation_id`),
  KEY `idx_model_usage_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='model_usage';

CREATE TABLE `artifact_asset` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '资产拥有者用户ID',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/tenant_public',
  `session_id` VARCHAR(64) NULL COMMENT '关联会话ID',
  `message_id` VARCHAR(64) NULL COMMENT '关联消息ID',
  `asset_id` VARCHAR(64) NOT NULL COMMENT '资产业务ID',
  `asset_kind` VARCHAR(64) NOT NULL DEFAULT 'artifact' COMMENT '资产业务类型：chat_attachment/artifact',
  `asset_type` VARCHAR(64) NOT NULL COMMENT '资产类型：image/file/pdf/excel/audio/video',
  `bucket` VARCHAR(128) NULL COMMENT '存储桶',
  `object_key` VARCHAR(512) NULL COMMENT '对象存储 Key',
  `file_name` VARCHAR(255) NULL COMMENT '文件名',
  `mime_type` VARCHAR(128) NULL COMMENT 'MIME 类型',
  `size_bytes` BIGINT NULL COMMENT '文件大小，单位字节',
  `sha256` CHAR(64) NULL COMMENT '文件内容 SHA-256',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '资产状态：active/deleted',
  `parse_status` VARCHAR(32) NOT NULL DEFAULT 'unsupported' COMMENT '解析状态：ready/failed/unsupported',
  `extracted_text` MEDIUMTEXT NULL COMMENT '安全截断后的附件文本',
  `parse_error` VARCHAR(512) NULL COMMENT '安全解析错误摘要',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_artifact_asset_id` (`asset_id`),
  KEY `idx_artifact_owner` (`owner_user_id`, `create_time`),
  KEY `idx_artifact_tenant_visibility` (`tenant_id`, `visibility`, `status`),
  KEY `idx_artifact_session` (`session_id`, `create_time`),
  KEY `idx_artifact_message` (`message_id`),
  KEY `idx_artifact_owner_hash` (`tenant_id`, `owner_user_id`, `sha256`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='artifact_asset';

CREATE TABLE `rag_knowledge_base` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '知识库拥有者用户ID',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/tenant_public',
  `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库业务ID',
  `kb_name` VARCHAR(128) NOT NULL COMMENT '知识库名称',
  `description` VARCHAR(512) NULL COMMENT '知识库描述',
  `embedding_model` VARCHAR(128) NULL COMMENT 'Embedding 模型',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '知识库状态：active/disabled/indexing',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_kb_id` (`kb_id`),
  KEY `idx_rag_kb_owner` (`owner_user_id`),
  KEY `idx_rag_kb_tenant_visibility` (`tenant_id`, `visibility`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='rag_knowledge_base';

CREATE TABLE `rag_document` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '文档上传者用户ID',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/tenant_public',
  `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库业务ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '文档业务ID',
  `file_name` VARCHAR(255) NULL COMMENT '文件名',
  `source_type` VARCHAR(64) NULL COMMENT '来源类型：upload/url/text/oss',
  `source_uri` VARCHAR(512) NULL COMMENT '来源地址',
  `content_hash` VARCHAR(128) NULL COMMENT '内容哈希',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '文档状态：active/indexing/indexed/failed/deleted',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_document_id` (`document_id`),
  KEY `idx_rag_document_kb` (`kb_id`, `create_time`),
  KEY `idx_rag_document_owner` (`owner_user_id`),
  KEY `idx_rag_document_tenant_visibility` (`tenant_id`, `visibility`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='rag_document';

CREATE TABLE `rag_chunk` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '切片归属用户ID',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/tenant_public',
  `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库业务ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '文档业务ID',
  `chunk_id` VARCHAR(64) NOT NULL COMMENT '切片业务ID',
  `chunk_index` INT NOT NULL DEFAULT 0 COMMENT '文档内切片序号',
  `content` LONGTEXT NULL COMMENT '切片内容',
  `token_count` INT NULL COMMENT '切片 token 数',
  `embedding_id` VARCHAR(128) NULL COMMENT '向量库中的向量ID',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '切片状态：active/deleted',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_chunk_id` (`chunk_id`),
  UNIQUE KEY `uk_rag_chunk_doc_idx` (`document_id`, `chunk_index`),
  KEY `idx_rag_chunk_doc` (`document_id`, `chunk_index`),
  KEY `idx_rag_chunk_kb` (`kb_id`),
  KEY `idx_rag_chunk_tenant_visibility` (`tenant_id`, `visibility`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='rag_chunk';

CREATE TABLE `skill_definition` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT 'Skill 拥有者用户ID',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/tenant_public',
  `skill_id` VARCHAR(64) NOT NULL COMMENT 'Skill 业务ID',
  `skill_name` VARCHAR(128) NOT NULL COMMENT 'Skill 名称',
  `skill_code` VARCHAR(128) NULL COMMENT 'Skill 编码',
  `description` VARCHAR(512) NULL COMMENT 'Skill 描述',
  `source_type` VARCHAR(64) NOT NULL COMMENT '来源类型：builtin/upload/git/markdown',
  `source_uri` VARCHAR(512) NULL COMMENT '来源地址',
  `version` VARCHAR(64) NOT NULL DEFAULT '1.0.0' COMMENT '版本号',
  `current_version` VARCHAR(64) NOT NULL DEFAULT '1.0.0' COMMENT '当前草稿版本号',
  `published_version` VARCHAR(64) NULL COMMENT '当前发布版本号',
  `active_version_id` VARCHAR(64) NULL COMMENT '当前生效版本业务ID',
  `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT 'Skill 状态：draft/active/disabled/archived/pending_review',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_id` (`skill_id`),
  UNIQUE KEY `uk_skill_code_version` (`tenant_id`, `skill_code`, `version`),
  KEY `idx_skill_owner` (`owner_user_id`, `create_time`),
  KEY `idx_skill_tenant_visibility` (`tenant_id`, `visibility`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='skill_definition';

CREATE TABLE `skill_version` (
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

CREATE TABLE `mcp_server_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT 'MCP 拥有者用户ID',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/tenant_public',
  `mcp_id` VARCHAR(64) NOT NULL COMMENT 'MCP 配置业务ID',
  `mcp_name` VARCHAR(128) NOT NULL COMMENT 'MCP 名称',
  `transport_type` VARCHAR(32) NOT NULL COMMENT '传输类型：stdio/sse/http',
  `endpoint` VARCHAR(512) NULL COMMENT '远程 MCP 地址，stdio 类型可为空',
  `command` VARCHAR(512) NULL COMMENT 'stdio 启动命令',
  `args` JSON NULL COMMENT 'stdio 启动参数',
  `env` JSON NULL COMMENT '运行环境变量，敏感值应加密或引用 user_secret',
  `description` VARCHAR(512) NULL COMMENT 'MCP 描述',
  `current_version` VARCHAR(64) NOT NULL DEFAULT '1.0.0' COMMENT '当前草稿版本号',
  `published_version` VARCHAR(64) NULL COMMENT '当前发布版本号',
  `active_version_id` VARCHAR(64) NULL COMMENT '当前生效版本业务ID',
  `test_status` VARCHAR(32) NOT NULL DEFAULT 'untested' COMMENT '测试状态：untested/success/failed',
  `test_message` VARCHAR(512) NULL COMMENT '最近一次测试结果说明',
  `last_test_time` DATETIME(3) NULL COMMENT '最近一次测试时间',
  `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT 'MCP 状态：draft/active/disabled/archived/pending_review',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_id` (`mcp_id`),
  KEY `idx_mcp_owner` (`owner_user_id`, `create_time`),
  KEY `idx_mcp_tenant_visibility` (`tenant_id`, `visibility`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='mcp_server_config';

CREATE TABLE `mcp_config_version` (
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

CREATE TABLE `tool_call_log` (
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

CREATE TABLE `agent_tenant_override` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `agent_id` VARCHAR(64) NOT NULL COMMENT '静态 Agent ID',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '租户覆盖状态：active/disabled',
  `reason` VARCHAR(256) NULL COMMENT '状态变更原因',
  `updated_by` VARCHAR(64) NOT NULL COMMENT '最后操作用户',
  `revision` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `disabled_at` DATETIME(3) NULL COMMENT '禁用时间',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tenant_override` (`tenant_id`, `agent_id`),
  KEY `idx_agent_override_status` (`tenant_id`, `status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户静态 Agent 状态覆盖';

CREATE TABLE `agent_workflow` (
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
  `deleted_by` VARCHAR(64) NULL COMMENT '删除操作用户',
  `deleted_at` DATETIME(3) NULL COMMENT '删除时间',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_workflow_tenant_workflow` (`tenant_id`, `workflow_id`),
  KEY `idx_agent_workflow_owner` (`owner_user_id`, `create_time`),
  KEY `idx_agent_workflow_tenant_status` (`tenant_id`, `status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent_workflow';

CREATE TABLE `agent_workflow_version` (
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

CREATE TABLE `agent_schedule_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `owner_user_id` VARCHAR(64) NOT NULL COMMENT '配置拥有者用户ID',
  `run_as_user_id` VARCHAR(64) NOT NULL COMMENT '任务执行身份用户ID，用于上下文、权限和用量归属',
  `visibility` VARCHAR(32) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/tenant_public',
  `config_id` VARCHAR(64) NOT NULL COMMENT '调度配置业务ID',
  `agent_id` VARCHAR(64) NULL COMMENT 'Agent ID',
  `agent_name` VARCHAR(128) NULL COMMENT 'Agent 名称',
  `cron_expr` VARCHAR(128) NOT NULL COMMENT 'Cron 表达式',
  `timezone` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '时区',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用，0否1是',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '配置状态：active/disabled/archived',
  `misfire_policy` VARCHAR(32) NOT NULL DEFAULT 'fire_once_now' COMMENT '错过策略',
  `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
  `config_hash` CHAR(64) NULL COMMENT '规范化配置摘要',
  `config_version` BIGINT NOT NULL DEFAULT 0 COMMENT '配置收敛版本',
  `last_reconciled_at` DATETIME(3) NULL COMMENT '最近对账时间',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_schedule_config_id` (`config_id`),
  KEY `idx_schedule_config_owner` (`owner_user_id`, `create_time`),
  KEY `idx_schedule_config_run_as` (`run_as_user_id`, `enabled`, `status`),
  KEY `idx_schedule_config_tenant_visibility` (`tenant_id`, `visibility`, `status`),
  KEY `idx_schedule_config_agent` (`agent_name`)
  ,KEY `idx_schedule_config_reconcile` (`last_reconciled_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent_schedule_config';

CREATE TABLE `agent_schedule_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `user_id` VARCHAR(64) NOT NULL COMMENT '任务归属用户ID，一般等于 run_as_user_id',
  `config_id` VARCHAR(64) NOT NULL COMMENT '调度配置业务ID',
  `task_id` VARCHAR(64) NOT NULL COMMENT '调度任务实例ID',
  `business_key` CHAR(64) NOT NULL COMMENT '租户与配置稳定业务键',
  `config_hash` CHAR(64) NULL COMMENT '当前配置摘要',
  `config_version` BIGINT NOT NULL DEFAULT 0 COMMENT '运行态配置版本',
  `cron_expr` VARCHAR(128) NULL COMMENT 'Cron 快照',
  `timezone` VARCHAR(64) NULL COMMENT '时区快照',
  `misfire_policy` VARCHAR(32) NOT NULL DEFAULT 'fire_once_now' COMMENT '错过策略快照',
  `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数快照',
  `planned_time` DATETIME(3) NOT NULL COMMENT '计划执行时间',
  `next_fire_time` DATETIME(3) NOT NULL COMMENT '下一计划时间 UTC',
  `last_planned_time` DATETIME(3) NULL COMMENT '上一计划时间 UTC',
  `retry_at` DATETIME(3) NULL COMMENT '失败重试时间 UTC',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '任务状态：pending/running/success/failed/canceled',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `lease_owner` VARCHAR(160) NULL COMMENT '租约持有者',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止时间 UTC',
  `fencing_token` BIGINT NOT NULL DEFAULT 0 COMMENT '单调栅栏令牌',
  `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '行版本',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_schedule_task_id` (`task_id`),
  UNIQUE KEY `uk_schedule_task_config` (`config_id`),
  UNIQUE KEY `uk_schedule_task_business` (`business_key`),
  KEY `idx_schedule_task_user` (`user_id`, `planned_time`),
  KEY `idx_schedule_task_config` (`config_id`, `planned_time`),
  KEY `idx_schedule_task_status` (`status`, `planned_time`)
  ,KEY `idx_schedule_task_due` (`status`, `retry_at`, `next_fire_time`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent_schedule_task';

CREATE TABLE `agent_schedule_execution` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `user_id` VARCHAR(64) NOT NULL COMMENT '执行归属用户ID，一般等于任务 user_id',
  `config_id` VARCHAR(64) NOT NULL COMMENT '调度配置业务ID',
  `task_id` VARCHAR(64) NOT NULL COMMENT '调度任务实例ID',
  `execution_id` VARCHAR(64) NOT NULL COMMENT '执行记录ID',
  `trigger_key` VARCHAR(180) NOT NULL COMMENT '计划触发点幂等键',
  `trace_id` VARCHAR(64) NULL COMMENT '链路ID',
  `planned_time` DATETIME(3) NOT NULL COMMENT '计划触发时间 UTC',
  `attempt_no` INT NOT NULL DEFAULT 1 COMMENT '当前尝试次数',
  `fencing_token` BIGINT NOT NULL DEFAULT 0 COMMENT '执行栅栏令牌',
  `lease_owner` VARCHAR(160) NULL COMMENT '执行租约持有者',
  `start_time` DATETIME(3) NULL COMMENT '执行开始时间',
  `end_time` DATETIME(3) NULL COMMENT '执行结束时间',
  `duration_ms` BIGINT NULL COMMENT '执行耗时，单位毫秒',
  `status` VARCHAR(32) NOT NULL DEFAULT 'running' COMMENT '执行状态：running/success/failed/canceled',
  `error_message` VARCHAR(1024) NULL COMMENT '错误信息',
  `result_json` LONGTEXT NULL COMMENT '白名单执行结果 JSON',
  `metadata` JSON NULL COMMENT '扩展信息',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除标记，0未删除，1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_schedule_execution_id` (`execution_id`),
  UNIQUE KEY `uk_schedule_execution_trigger` (`trigger_key`),
  KEY `idx_schedule_execution_user` (`user_id`, `start_time`),
  KEY `idx_schedule_execution_task` (`task_id`, `start_time`),
  KEY `idx_schedule_execution_trace` (`trace_id`),
  KEY `idx_schedule_execution_status` (`status`, `start_time`)
  ,KEY `idx_schedule_execution_config` (`config_id`, `planned_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent_schedule_execution';

SET FOREIGN_KEY_CHECKS = 1;
