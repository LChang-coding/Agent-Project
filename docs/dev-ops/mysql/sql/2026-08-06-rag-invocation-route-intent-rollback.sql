-- 破坏性结构回滚，仅在应用已回切且无在途 TOOL_V2 运行后执行。脚本可重复执行。
DROP TABLE IF EXISTS workflow_route_intent;

SET @schema_name = DATABASE();

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_run' AND COLUMN_NAME='rag_invocation_mode')=1,
              'ALTER TABLE chat_run DROP COLUMN rag_invocation_mode',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='chat_session' AND COLUMN_NAME='rag_invocation_mode')=1,
              'ALTER TABLE chat_session DROP COLUMN rag_invocation_mode',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
