-- 会话RAG模式、乐观锁版本与手动绑定选择。脚本可重复执行。
SET @schema_name = DATABASE();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_session' AND COLUMN_NAME='rag_mode')=0,
              'ALTER TABLE chat_session ADD COLUMN rag_mode VARCHAR(16) NOT NULL DEFAULT ''OFF'' COMMENT ''会话RAG模式：OFF/AUTO/MANUAL'' AFTER rag_enabled',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_session' AND COLUMN_NAME='rag_revision')=0,
              'ALTER TABLE chat_session ADD COLUMN rag_revision BIGINT NOT NULL DEFAULT 0 COMMENT ''会话RAG策略乐观锁版本'' AFTER rag_mode',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 新列默认OFF；仅将仍保持旧开关开启的历史会话回填为AUTO。
UPDATE chat_session
SET rag_mode = 'AUTO'
WHERE rag_enabled = 1 AND rag_mode = 'OFF' AND deleted = 0;

CREATE TABLE IF NOT EXISTS `chat_session_rag_binding_selection` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NULL COMMENT '租户业务ID，个体户可为空',
  `user_id` VARCHAR(64) NOT NULL COMMENT '会话归属用户ID',
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话业务ID',
  `target_type` VARCHAR(32) NOT NULL COMMENT '绑定目标类型：agent/workflow',
  `target_id` VARCHAR(64) NOT NULL COMMENT 'Agent或工作流业务ID',
  `binding_id` VARCHAR(64) NOT NULL COMMENT 'RAG绑定业务ID',
  `selection_order` INT NOT NULL DEFAULT 0 COMMENT '会话内选择顺序',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_rag_selection` (`session_id`, `binding_id`),
  KEY `idx_session_rag_scope` (`tenant_id`, `user_id`, `session_id`, `selection_order`),
  KEY `idx_session_rag_binding` (`tenant_id`, `binding_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话RAG手动绑定选择';

-- Run必须冻结“展开后的有效绑定”，不能只保存布尔开关。
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_run' AND COLUMN_NAME='rag_mode')=0,
              'ALTER TABLE chat_run ADD COLUMN rag_mode VARCHAR(16) NOT NULL DEFAULT ''OFF'' COMMENT ''运行RAG模式快照'' AFTER rag_enabled',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_run' AND COLUMN_NAME='rag_policy_revision')=0,
              'ALTER TABLE chat_run ADD COLUMN rag_policy_revision BIGINT NOT NULL DEFAULT 0 COMMENT ''运行RAG策略版本快照'' AFTER rag_mode',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_run' AND COLUMN_NAME='rag_binding_ids_json')=0,
              'ALTER TABLE chat_run ADD COLUMN rag_binding_ids_json JSON NULL COMMENT ''运行有效RAG绑定ID快照'' AFTER rag_policy_revision',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE chat_run
SET rag_mode = CASE WHEN rag_enabled = 1 THEN 'AUTO' ELSE 'OFF' END,
    rag_policy_revision = COALESCE(rag_policy_revision, 0),
    rag_binding_ids_json = COALESCE(rag_binding_ids_json, JSON_ARRAY())
WHERE deleted = 0
  AND (rag_mode = 'OFF' OR rag_binding_ids_json IS NULL);
