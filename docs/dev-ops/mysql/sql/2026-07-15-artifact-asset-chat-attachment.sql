-- 聊天附件与资产中心增量迁移（MySQL 8，可重复执行）。
-- 回退策略：应用回退后保留新增可空列；如必须移除，另建前向迁移在确认无读写后逐列删除。

SET @schema_name = DATABASE();

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'artifact_asset' AND COLUMN_NAME = 'asset_kind') = 0,
              'ALTER TABLE artifact_asset ADD COLUMN asset_kind VARCHAR(64) NOT NULL DEFAULT ''artifact'' COMMENT ''资产业务类型'' AFTER asset_id',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'artifact_asset' AND COLUMN_NAME = 'sha256') = 0,
              'ALTER TABLE artifact_asset ADD COLUMN sha256 CHAR(64) NULL COMMENT ''文件内容 SHA-256'' AFTER size_bytes',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'artifact_asset' AND COLUMN_NAME = 'parse_status') = 0,
              'ALTER TABLE artifact_asset ADD COLUMN parse_status VARCHAR(32) NOT NULL DEFAULT ''unsupported'' COMMENT ''解析状态'' AFTER status',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'artifact_asset' AND COLUMN_NAME = 'extracted_text') = 0,
              'ALTER TABLE artifact_asset ADD COLUMN extracted_text MEDIUMTEXT NULL COMMENT ''安全截断后的附件文本'' AFTER parse_status',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'artifact_asset' AND COLUMN_NAME = 'parse_error') = 0,
              'ALTER TABLE artifact_asset ADD COLUMN parse_error VARCHAR(512) NULL COMMENT ''安全解析错误摘要'' AFTER extracted_text',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'artifact_asset' AND INDEX_NAME = 'idx_artifact_owner_hash') = 0,
              'CREATE INDEX idx_artifact_owner_hash ON artifact_asset (tenant_id, owner_user_id, sha256, status)',
              'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
