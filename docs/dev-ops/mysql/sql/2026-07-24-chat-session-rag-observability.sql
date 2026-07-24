-- 会话RAG开关与逐轮链路快照。脚本可重复执行。
SET @schema_name = DATABASE();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_session' AND COLUMN_NAME='rag_enabled')=0,
              'ALTER TABLE chat_session ADD COLUMN rag_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否启用会话RAG'' AFTER status',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='rag_outbox' AND COLUMN_NAME='trace_id')=0,
              'ALTER TABLE rag_outbox ADD COLUMN trace_id VARCHAR(64) NULL COMMENT ''原始请求链路ID'' AFTER payload',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_run' AND COLUMN_NAME='rag_enabled')=0,
              'ALTER TABLE chat_run ADD COLUMN rag_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''运行创建时的RAG开关快照'' AFTER source_id',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_run' AND COLUMN_NAME='trace_id')=0,
              'ALTER TABLE chat_run ADD COLUMN trace_id VARCHAR(64) NULL COMMENT ''运行根链路ID'' AFTER rag_enabled',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_run' AND INDEX_NAME='idx_chat_run_trace')=0,
              'ALTER TABLE chat_run ADD KEY idx_chat_run_trace (trace_id)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
