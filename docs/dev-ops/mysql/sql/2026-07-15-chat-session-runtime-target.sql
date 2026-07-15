-- 会话运行目标事实源增量迁移（MySQL 8，可重复执行）。
-- 旧数据无法可靠反推工作流身份，按兼容约定统一保留为 agent。
SET @schema_name = DATABASE();

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name
               AND TABLE_NAME='chat_session' AND COLUMN_NAME='source_type')=0,
              'ALTER TABLE chat_session ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT ''agent'' COMMENT ''运行目标类型：agent/workflow'' AFTER agent_name',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name
               AND TABLE_NAME='chat_session' AND COLUMN_NAME='workflow_version')=0,
              'ALTER TABLE chat_session ADD COLUMN workflow_version INT NULL COMMENT ''工作流实际运行版本'' AFTER source_type',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name
               AND TABLE_NAME='chat_session' AND COLUMN_NAME='model_code')=0,
              'ALTER TABLE chat_session ADD COLUMN model_code VARCHAR(128) NULL COMMENT ''工作流实际运行模型编码'' AFTER workflow_version',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE chat_session SET source_type = 'agent'
WHERE source_type IS NULL OR TRIM(source_type) = '' OR source_type NOT IN ('agent', 'workflow');
