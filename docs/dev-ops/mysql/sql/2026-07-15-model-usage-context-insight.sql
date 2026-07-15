-- 模型用量调用级闭环增量迁移。
-- 生产环境执行前必须备份 model_usage 表结构与数据，并记录本文件 SHA-256。

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'model_usage' AND column_name = 'run_id'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE model_usage ADD COLUMN run_id VARCHAR(64) NULL COMMENT ''业务运行ID'' AFTER session_id',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'model_usage' AND column_name = 'call_id'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE model_usage ADD COLUMN call_id VARCHAR(96) NULL COMMENT ''单次模型调用幂等ID'' AFTER run_id',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'model_usage' AND column_name = 'usage_type'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE model_usage ADD COLUMN usage_type VARCHAR(32) NOT NULL DEFAULT ''chat'' COMMENT ''用量类型'' AFTER model_version',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'model_usage' AND column_name = 'call_status'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE model_usage ADD COLUMN call_status VARCHAR(32) NOT NULL DEFAULT ''success'' COMMENT ''调用终态'' AFTER usage_type',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'model_usage' AND column_name = 'finish_reason'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE model_usage ADD COLUMN finish_reason VARCHAR(128) NULL COMMENT ''模型结束原因'' AFTER call_status',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'model_usage' AND index_name = 'uk_model_usage_call'
);
SET @ddl = IF(@index_exists = 0,
    'ALTER TABLE model_usage ADD UNIQUE KEY uk_model_usage_call (call_id)',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'model_usage' AND index_name = 'idx_model_usage_run'
);
SET @ddl = IF(@index_exists = 0,
    'ALTER TABLE model_usage ADD KEY idx_model_usage_run (tenant_id, user_id, session_id, run_id, create_time)',
    'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;
