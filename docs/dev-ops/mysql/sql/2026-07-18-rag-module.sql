-- RAG 模块增量结构迁移（MySQL 8，可重复执行）。
--
-- 上线原则：
-- 1. 本文件只做结构扩展，不删除业务表、不删除业务数据、不回填虚构租户。
-- 2. 三张历史占位表在变更为强租户隔离前先执行阻断式审计；存在 NULL、空租户、
--    联合键重复或跨租户孤儿数据时立即 SIGNAL 终止，必须先人工治理数据。
-- 3. MySQL DDL 会隐式提交，不能依赖事务整体回滚；本文件通过 information_schema
--    判断列和索引是否存在，使成功执行过的步骤可安全跳过后续重跑。
-- 4. 新列采用 expand-contract：业务上线前允许对象位置等列为空，待应用双写、回填并核验后，
--    再使用新的前向迁移收紧约束，禁止直接修改本文件。
-- 5. 所有 DATETIME(3) 均由应用按 UTC 写入。
-- 6. 本文件不内置 USE；执行方必须显式选定目标数据库，避免测试或发布误写其他库。

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 一、上线前审计（先展示统计，再阻断不安全迁移）
-- -----------------------------------------------------------------------------

SELECT 'rag_knowledge_base.tenant_missing' AS audit_item, COUNT(*) AS issue_count
FROM rag_knowledge_base WHERE tenant_id IS NULL OR TRIM(tenant_id) = ''
UNION ALL
SELECT 'rag_document.tenant_missing', COUNT(*)
FROM rag_document WHERE tenant_id IS NULL OR TRIM(tenant_id) = ''
UNION ALL
SELECT 'rag_chunk.tenant_missing', COUNT(*)
FROM rag_chunk WHERE tenant_id IS NULL OR TRIM(tenant_id) = ''
UNION ALL
SELECT 'rag_knowledge_base.tenant_kb_duplicate', COUNT(*)
FROM (
    SELECT tenant_id, kb_id FROM rag_knowledge_base
    GROUP BY tenant_id, kb_id HAVING COUNT(*) > 1
) duplicate_kb
UNION ALL
SELECT 'rag_knowledge_base.tenant_name_duplicate', COUNT(*)
FROM (
    SELECT tenant_id, kb_name FROM rag_knowledge_base
    GROUP BY tenant_id, kb_name HAVING COUNT(*) > 1
) duplicate_kb_name
UNION ALL
SELECT 'rag_document.tenant_document_duplicate', COUNT(*)
FROM (
    SELECT tenant_id, document_id FROM rag_document
    GROUP BY tenant_id, document_id HAVING COUNT(*) > 1
) duplicate_document
UNION ALL
SELECT 'rag_chunk.tenant_chunk_duplicate', COUNT(*)
FROM (
    SELECT tenant_id, chunk_id FROM rag_chunk
    GROUP BY tenant_id, chunk_id HAVING COUNT(*) > 1
) duplicate_chunk
UNION ALL
SELECT 'rag_chunk.tenant_document_index_duplicate', COUNT(*)
FROM (
    SELECT tenant_id, document_id, chunk_index FROM rag_chunk
    GROUP BY tenant_id, document_id, chunk_index HAVING COUNT(*) > 1
) duplicate_document_index
UNION ALL
SELECT 'rag_document.orphan_knowledge_base', COUNT(*)
FROM rag_document document
LEFT JOIN rag_knowledge_base knowledge_base
  ON knowledge_base.tenant_id <=> document.tenant_id
 AND knowledge_base.kb_id = document.kb_id
 AND knowledge_base.deleted = 0
WHERE document.deleted = 0 AND knowledge_base.id IS NULL
UNION ALL
SELECT 'rag_chunk.orphan_document', COUNT(*)
FROM rag_chunk chunk_record
LEFT JOIN rag_document document
  ON document.tenant_id <=> chunk_record.tenant_id
 AND document.document_id = chunk_record.document_id
 AND document.kb_id = chunk_record.kb_id
 AND document.deleted = 0
WHERE chunk_record.deleted = 0 AND document.id IS NULL;

DROP PROCEDURE IF EXISTS `sp_rag_assert_preconditions_20260718`;
DELIMITER $$
CREATE PROCEDURE `sp_rag_assert_preconditions_20260718`()
BEGIN
    DECLARE issue_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO issue_count
    FROM rag_knowledge_base WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：知识库存在空租户，请先人工归属';
    END IF;

    SELECT COUNT(*) INTO issue_count
    FROM rag_document WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：文档存在空租户，请先人工归属';
    END IF;

    SELECT COUNT(*) INTO issue_count
    FROM rag_chunk WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：切片存在空租户，请先人工归属';
    END IF;

    SELECT COUNT(*) INTO issue_count FROM (
        SELECT tenant_id, kb_id FROM rag_knowledge_base
        GROUP BY tenant_id, kb_id HAVING COUNT(*) > 1
    ) duplicate_kb;
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：知识库租户联合键重复';
    END IF;

    SELECT COUNT(*) INTO issue_count FROM (
        SELECT tenant_id, kb_name FROM rag_knowledge_base
        GROUP BY tenant_id, kb_name HAVING COUNT(*) > 1
    ) duplicate_kb_name;
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：同租户知识库名称重复';
    END IF;

    SELECT COUNT(*) INTO issue_count FROM (
        SELECT tenant_id, document_id FROM rag_document
        GROUP BY tenant_id, document_id HAVING COUNT(*) > 1
    ) duplicate_document;
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：文档租户联合键重复';
    END IF;

    SELECT COUNT(*) INTO issue_count FROM (
        SELECT tenant_id, chunk_id FROM rag_chunk
        GROUP BY tenant_id, chunk_id HAVING COUNT(*) > 1
    ) duplicate_chunk;
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：切片租户联合键重复';
    END IF;

    SELECT COUNT(*) INTO issue_count FROM (
        SELECT tenant_id, document_id, chunk_index FROM rag_chunk
        GROUP BY tenant_id, document_id, chunk_index HAVING COUNT(*) > 1
    ) duplicate_document_index;
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：文档切片序号重复';
    END IF;

    SELECT COUNT(*) INTO issue_count
    FROM rag_document document
    LEFT JOIN rag_knowledge_base knowledge_base
      ON knowledge_base.tenant_id = document.tenant_id
     AND knowledge_base.kb_id = document.kb_id
     AND knowledge_base.deleted = 0
    WHERE document.deleted = 0 AND knowledge_base.id IS NULL;
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：存在无知识库归属的文档';
    END IF;

    SELECT COUNT(*) INTO issue_count
    FROM rag_chunk chunk_record
    LEFT JOIN rag_document document
      ON document.tenant_id = chunk_record.tenant_id
     AND document.document_id = chunk_record.document_id
     AND document.kb_id = chunk_record.kb_id
     AND document.deleted = 0
    WHERE chunk_record.deleted = 0 AND document.id IS NULL;
    IF issue_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'RAG迁移终止：存在无文档归属的切片';
    END IF;
END$$
DELIMITER ;

CALL `sp_rag_assert_preconditions_20260718`();
DROP PROCEDURE IF EXISTS `sp_rag_assert_preconditions_20260718`;

-- -----------------------------------------------------------------------------
-- 二、幂等 DDL 辅助过程
-- -----------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS `sp_rag_add_column_20260718`;
DROP PROCEDURE IF EXISTS `sp_rag_add_index_20260718`;
DROP PROCEDURE IF EXISTS `sp_rag_drop_index_20260718`;
DROP PROCEDURE IF EXISTS `sp_rag_make_not_null_20260718`;
DELIMITER $$
CREATE PROCEDURE `sp_rag_add_column_20260718`(
    IN table_name_value VARCHAR(64),
    IN column_name_value VARCHAR(64),
    IN column_definition_value TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = table_name_value
          AND COLUMN_NAME = column_name_value
    ) THEN
        SET @rag_ddl = CONCAT('ALTER TABLE `', REPLACE(table_name_value, '`', '``'),
                              '` ADD COLUMN `', REPLACE(column_name_value, '`', '``'),
                              '` ', column_definition_value);
        PREPARE rag_statement FROM @rag_ddl;
        EXECUTE rag_statement;
        DEALLOCATE PREPARE rag_statement;
    END IF;
END$$

CREATE PROCEDURE `sp_rag_add_index_20260718`(
    IN table_name_value VARCHAR(64),
    IN index_name_value VARCHAR(64),
    IN index_definition_value TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = table_name_value
          AND INDEX_NAME = index_name_value
    ) THEN
        SET @rag_ddl = CONCAT('ALTER TABLE `', REPLACE(table_name_value, '`', '``'),
                              '` ADD ', index_definition_value);
        PREPARE rag_statement FROM @rag_ddl;
        EXECUTE rag_statement;
        DEALLOCATE PREPARE rag_statement;
    END IF;
END$$

CREATE PROCEDURE `sp_rag_drop_index_20260718`(
    IN table_name_value VARCHAR(64),
    IN index_name_value VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = table_name_value
          AND INDEX_NAME = index_name_value
          AND INDEX_NAME <> 'PRIMARY'
    ) THEN
        SET @rag_ddl = CONCAT('ALTER TABLE `', REPLACE(table_name_value, '`', '``'),
                              '` DROP INDEX `', REPLACE(index_name_value, '`', '``'), '`');
        PREPARE rag_statement FROM @rag_ddl;
        EXECUTE rag_statement;
        DEALLOCATE PREPARE rag_statement;
    END IF;
END$$

CREATE PROCEDURE `sp_rag_make_not_null_20260718`(
    IN table_name_value VARCHAR(64),
    IN column_name_value VARCHAR(64),
    IN column_definition_value TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = table_name_value
          AND COLUMN_NAME = column_name_value
          AND IS_NULLABLE = 'YES'
    ) THEN
        SET @rag_ddl = CONCAT('ALTER TABLE `', REPLACE(table_name_value, '`', '``'),
                              '` MODIFY COLUMN `', REPLACE(column_name_value, '`', '``'),
                              '` ', column_definition_value);
        PREPARE rag_statement FROM @rag_ddl;
        EXECUTE rag_statement;
        DEALLOCATE PREPARE rag_statement;
    END IF;
END$$
DELIMITER ;

-- -----------------------------------------------------------------------------
-- 三、扩展现有知识库、文档和切片表
-- -----------------------------------------------------------------------------

CALL `sp_rag_add_column_20260718`('rag_knowledge_base', 'embedding_dimension',
    'INT NOT NULL DEFAULT 768 COMMENT ''向量维度'' AFTER `embedding_model`');
CALL `sp_rag_add_column_20260718`('rag_knowledge_base', 'collection_alias',
    'VARCHAR(128) NULL COMMENT ''Qdrant 稳定集合别名'' AFTER `embedding_dimension`');
CALL `sp_rag_add_column_20260718`('rag_knowledge_base', 'current_generation',
    'BIGINT NOT NULL DEFAULT 0 COMMENT ''当前可见索代引代'' AFTER `collection_alias`');
CALL `sp_rag_add_column_20260718`('rag_knowledge_base', 'retrieval_profile_id',
    'VARCHAR(64) NULL COMMENT ''默认检索策略ID'' AFTER `current_generation`');
CALL `sp_rag_add_column_20260718`('rag_knowledge_base', 'revision',
    'BIGINT NOT NULL DEFAULT 1 COMMENT ''配置乐观锁版本'' AFTER `retrieval_profile_id`');

CALL `sp_rag_add_column_20260718`('rag_document', 'asset_id',
    'VARCHAR(64) NULL COMMENT ''关联原始资产业务ID'' AFTER `document_id`');
CALL `sp_rag_add_column_20260718`('rag_document', 'source_bucket',
    'VARCHAR(128) NULL COMMENT ''原始文件存储桶'' AFTER `source_uri`');
CALL `sp_rag_add_column_20260718`('rag_document', 'source_object_key',
    'VARCHAR(512) NULL COMMENT ''原始文件对象Key'' AFTER `source_bucket`');
CALL `sp_rag_add_column_20260718`('rag_document', 'mime_type',
    'VARCHAR(128) NULL COMMENT ''原始文件MIME'' AFTER `source_object_key`');
CALL `sp_rag_add_column_20260718`('rag_document', 'size_bytes',
    'BIGINT NULL COMMENT ''原始文件字节数'' AFTER `mime_type`');
CALL `sp_rag_add_column_20260718`('rag_document', 'document_version',
    'INT NOT NULL DEFAULT 1 COMMENT ''当前文档版本号'' AFTER `content_hash`');
CALL `sp_rag_add_column_20260718`('rag_document', 'active_generation',
    'BIGINT NOT NULL DEFAULT 0 COMMENT ''当前对检索可见的索代引代'' AFTER `document_version`');
CALL `sp_rag_add_column_20260718`('rag_document', 'active_version_id',
    'VARCHAR(64) NULL COMMENT ''当前对检索可见的文档版本ID'' AFTER `active_generation`');
CALL `sp_rag_add_column_20260718`('rag_document', 'target_generation',
    'BIGINT NULL COMMENT ''正在构建的目标索代引代'' AFTER `active_version_id`');
CALL `sp_rag_add_column_20260718`('rag_document', 'parser_name',
    'VARCHAR(64) NULL COMMENT ''解析器名称'' AFTER `target_generation`');
CALL `sp_rag_add_column_20260718`('rag_document', 'parser_version',
    'VARCHAR(64) NULL COMMENT ''解析器版本'' AFTER `parser_name`');
CALL `sp_rag_add_column_20260718`('rag_document', 'page_count',
    'INT NULL COMMENT ''解析页数'' AFTER `parser_version`');
CALL `sp_rag_add_column_20260718`('rag_document', 'chunk_count',
    'INT NOT NULL DEFAULT 0 COMMENT ''当前可见切片数'' AFTER `page_count`');
CALL `sp_rag_add_column_20260718`('rag_document', 'last_error_code',
    'VARCHAR(64) NULL COMMENT ''最近错误码'' AFTER `chunk_count`');
CALL `sp_rag_add_column_20260718`('rag_document', 'last_error_message',
    'VARCHAR(1000) NULL COMMENT ''脱敏后的最近错误摘要'' AFTER `last_error_code`');
CALL `sp_rag_add_column_20260718`('rag_document', 'indexed_at',
    'DATETIME(3) NULL COMMENT ''最近成功入索引时间UTC'' AFTER `last_error_message`');
CALL `sp_rag_add_column_20260718`('rag_document', 'revision',
    'BIGINT NOT NULL DEFAULT 1 COMMENT ''状态乐观锁版本'' AFTER `indexed_at`');

CALL `sp_rag_add_column_20260718`('rag_chunk', 'document_version',
    'INT NOT NULL DEFAULT 1 COMMENT ''所属文档版本'' AFTER `document_id`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'version_id',
    'VARCHAR(64) NULL COMMENT ''所属文档版本业务ID'' AFTER `document_version`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'generation',
    'BIGINT NOT NULL DEFAULT 1 COMMENT ''索引构建代引代'' AFTER `version_id`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'parent_chunk_id',
    'VARCHAR(64) NULL COMMENT ''父级切片ID'' AFTER `chunk_index`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'previous_chunk_id',
    'VARCHAR(64) NULL COMMENT ''前一切片ID'' AFTER `parent_chunk_id`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'next_chunk_id',
    'VARCHAR(64) NULL COMMENT ''后一切片ID'' AFTER `previous_chunk_id`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'section_path',
    'VARCHAR(1000) NULL COMMENT ''标题层级路径'' AFTER `next_chunk_id`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'page_from',
    'INT NULL COMMENT ''起始页码，从1开始'' AFTER `section_path`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'page_to',
    'INT NULL COMMENT ''结束页码，从1开始'' AFTER `page_from`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'char_start',
    'INT NULL COMMENT ''规范化文档内起始字符偏移'' AFTER `page_to`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'char_end',
    'INT NULL COMMENT ''规范化文档内结束字符偏移'' AFTER `char_start`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'content_hash',
    'CHAR(64) NULL COMMENT ''切片正文SHA-256'' AFTER `content`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'vector_point_id',
    'VARCHAR(128) NULL COMMENT ''Qdrant确定性Point ID'' AFTER `embedding_id`');
CALL `sp_rag_add_column_20260718`('rag_chunk', 'revision',
    'BIGINT NOT NULL DEFAULT 1 COMMENT ''切片乐观锁版本'' AFTER `status`');

-- 审计已保证无空租户，现收紧企业 RAG 的强租户边界。
CALL `sp_rag_make_not_null_20260718`('rag_knowledge_base', 'tenant_id',
    'VARCHAR(64) NOT NULL COMMENT ''租户业务ID''');
CALL `sp_rag_make_not_null_20260718`('rag_document', 'tenant_id',
    'VARCHAR(64) NOT NULL COMMENT ''租户业务ID''');
CALL `sp_rag_make_not_null_20260718`('rag_chunk', 'tenant_id',
    'VARCHAR(64) NOT NULL COMMENT ''租户业务ID''');

-- 先建立联合唯一键，再移除历史全局唯一键；任何一步失败都可安全重跑。
CALL `sp_rag_add_index_20260718`('rag_knowledge_base', 'uk_rag_kb_tenant_id',
    'UNIQUE KEY `uk_rag_kb_tenant_id` (`tenant_id`, `kb_id`)');
CALL `sp_rag_add_index_20260718`('rag_knowledge_base', 'uk_rag_kb_tenant_name',
    'UNIQUE KEY `uk_rag_kb_tenant_name` (`tenant_id`, `kb_name`)');
CALL `sp_rag_add_index_20260718`('rag_document', 'uk_rag_document_tenant_id',
    'UNIQUE KEY `uk_rag_document_tenant_id` (`tenant_id`, `document_id`)');
CALL `sp_rag_add_index_20260718`('rag_chunk', 'uk_rag_chunk_tenant_id',
    'UNIQUE KEY `uk_rag_chunk_tenant_id` (`tenant_id`, `chunk_id`)');
CALL `sp_rag_add_index_20260718`('rag_chunk', 'uk_rag_chunk_doc_generation_idx',
    'UNIQUE KEY `uk_rag_chunk_doc_generation_idx` (`tenant_id`, `document_id`, `generation`, `chunk_index`)');

CALL `sp_rag_drop_index_20260718`('rag_knowledge_base', 'uk_rag_kb_id');
CALL `sp_rag_drop_index_20260718`('rag_document', 'uk_rag_document_id');
CALL `sp_rag_drop_index_20260718`('rag_chunk', 'uk_rag_chunk_id');
CALL `sp_rag_drop_index_20260718`('rag_chunk', 'uk_rag_chunk_doc_idx');

CALL `sp_rag_add_index_20260718`('rag_knowledge_base', 'idx_rag_kb_tenant_status_update',
    'KEY `idx_rag_kb_tenant_status_update` (`tenant_id`, `status`, `update_time`)');
CALL `sp_rag_add_index_20260718`('rag_document', 'idx_rag_document_tenant_kb_status',
    'KEY `idx_rag_document_tenant_kb_status` (`tenant_id`, `kb_id`, `status`, `id`)');
CALL `sp_rag_add_index_20260718`('rag_document', 'idx_rag_document_tenant_hash',
    'KEY `idx_rag_document_tenant_hash` (`tenant_id`, `kb_id`, `content_hash`)');
CALL `sp_rag_add_index_20260718`('rag_chunk', 'idx_rag_chunk_retrieval_scope',
    'KEY `idx_rag_chunk_retrieval_scope` (`tenant_id`, `kb_id`, `generation`, `status`, `id`)');
CALL `sp_rag_add_index_20260718`('rag_chunk', 'idx_rag_chunk_document_version',
    'KEY `idx_rag_chunk_document_version` (`tenant_id`, `document_id`, `document_version`, `generation`, `chunk_index`)');

-- -----------------------------------------------------------------------------
-- 四、文档版本与可靠摄取账本
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `rag_document_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库业务ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '文档业务ID',
  `version_id` VARCHAR(64) NOT NULL COMMENT '文档版本业务ID',
  `version_no` INT NOT NULL COMMENT '单文档递增版本号',
  `generation` BIGINT NOT NULL COMMENT '本版本目标索代引代',
  `asset_id` VARCHAR(64) NULL COMMENT '关联原始资产业务ID',
  `source_bucket` VARCHAR(128) NOT NULL COMMENT '原始文件存储桶',
  `source_object_key` VARCHAR(512) NOT NULL COMMENT '原始文件对象Key',
  `file_name` VARCHAR(255) NOT NULL COMMENT '安全文件名',
  `mime_type` VARCHAR(128) NOT NULL COMMENT '文件MIME',
  `size_bytes` BIGINT NOT NULL COMMENT '文件字节数',
  `content_hash` CHAR(64) NOT NULL COMMENT '原始文件SHA-256',
  `parser_name` VARCHAR(64) NULL COMMENT '解析器名称',
  `parser_version` VARCHAR(64) NULL COMMENT '解析器版本',
  `chunker_version` VARCHAR(64) NULL COMMENT '分块器版本',
  `embedding_model_revision` VARCHAR(128) NULL COMMENT 'Embedding模型固定版本',
  `parsed_bucket` VARCHAR(128) NULL COMMENT '解析产物存储桶',
  `parsed_object_key` VARCHAR(512) NULL COMMENT '结构化解析产物对象Key',
  `page_count` INT NULL COMMENT '页数',
  `character_count` BIGINT NULL COMMENT '规范化字符数',
  `chunk_count` INT NOT NULL DEFAULT 0 COMMENT '切片数',
  `status` VARCHAR(32) NOT NULL DEFAULT 'created' COMMENT 'created/queued/processing/ready/failed/cancelled/superseded/deleting/deleted',
  `metadata` JSON NULL COMMENT '不承载状态机的扩展元数据',
  `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '状态乐观锁版本',
  `indexed_at` DATETIME(3) NULL COMMENT '成功入索引时间UTC',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_doc_version_id` (`tenant_id`, `version_id`),
  UNIQUE KEY `uk_rag_doc_version_no` (`tenant_id`, `document_id`, `version_no`),
  UNIQUE KEY `uk_rag_doc_generation` (`tenant_id`, `document_id`, `generation`),
  KEY `idx_rag_doc_version_kb` (`tenant_id`, `kb_id`, `status`, `id`),
  KEY `idx_rag_doc_version_hash` (`tenant_id`, `kb_id`, `content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG文档不可变版本';

CREATE TABLE IF NOT EXISTS `rag_ingest_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` VARCHAR(64) NOT NULL COMMENT '摄取任务业务ID',
  `task_key` CHAR(64) NOT NULL COMMENT '幂等任务键SHA-256',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库业务ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '文档业务ID',
  `version_id` VARCHAR(64) NULL COMMENT '文档版本业务ID',
  `document_version` INT NULL COMMENT '文档版本号',
  `generation` BIGINT NOT NULL COMMENT '本任务操作的索代引代',
  `operation` VARCHAR(32) NOT NULL COMMENT 'ingest/rebuild/delete',
  `stage` VARCHAR(32) NOT NULL DEFAULT 'received' COMMENT 'received/parsing/chunking/embedding/indexing/verifying/completed',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/retrying/cancel_requested/cancelled/completed/failed/dead',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '已领取次数',
  `max_attempts` INT NOT NULL DEFAULT 3 COMMENT '最大领取次数',
  `next_retry_at` DATETIME(3) NULL COMMENT '下一次可重试时间UTC',
  `lease_owner` VARCHAR(160) NULL COMMENT '租约持有实例',
  `lease_until` DATETIME(3) NULL COMMENT '租约截止时间UTC',
  `heartbeat_at` DATETIME(3) NULL COMMENT '最近一次续租心跳时间UTC',
  `fencing_token` BIGINT NOT NULL DEFAULT 0 COMMENT '单调栅栏令牌',
  `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '行乐观锁版本',
  `checkpoint` JSON NULL COMMENT '可恢复阶段检查点，不存密钥和正文',
  `cancel_requested_at` DATETIME(3) NULL COMMENT '取消请求时间UTC',
  `cancel_reason` VARCHAR(512) NULL COMMENT '脱敏后的取消原因',
  `cancelled_at` DATETIME(3) NULL COMMENT '取消完成时间UTC',
  `error_code` VARCHAR(64) NULL COMMENT '稳定错误码',
  `error_message` VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  `trace_id` VARCHAR(64) NULL COMMENT '链路追踪ID',
  `started_at` DATETIME(3) NULL COMMENT '首次开始时间UTC',
  `finished_at` DATETIME(3) NULL COMMENT '最终完成时间UTC',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_ingest_task_id` (`tenant_id`, `task_id`),
  UNIQUE KEY `uk_rag_ingest_task_key` (`tenant_id`, `task_key`),
  KEY `idx_rag_ingest_due` (`status`, `next_retry_at`, `lease_until`, `id`),
  KEY `idx_rag_ingest_document` (`tenant_id`, `document_id`, `generation`, `id`),
  KEY `idx_rag_ingest_kb` (`tenant_id`, `kb_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG摄取与清理任务账本';

CREATE TABLE IF NOT EXISTS `rag_outbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id` VARCHAR(64) NOT NULL COMMENT '事件业务ID',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `task_id` VARCHAR(64) NOT NULL COMMENT '关联摄取任务ID',
  `aggregate_type` VARCHAR(64) NOT NULL DEFAULT 'rag_ingest_task' COMMENT '聚合类型',
  `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合业务ID',
  `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型及版本',
  `topic_name` VARCHAR(255) NOT NULL COMMENT '目标Topic',
  `partition_key` VARCHAR(255) NOT NULL COMMENT 'Kafka分区键',
  `payload` JSON NOT NULL COMMENT '不含密钥和文档正文的事件载荷',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/publishing/published/retrying/dead',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '发布尝试次数',
  `max_attempts` INT NOT NULL DEFAULT 10 COMMENT '最大发布尝试次数',
  `next_retry_at` DATETIME(3) NULL COMMENT '下一次重试时间UTC',
  `lease_owner` VARCHAR(160) NULL COMMENT '发布租约持有者',
  `lease_until` DATETIME(3) NULL COMMENT '发布租约截止时间UTC',
  `heartbeat_at` DATETIME(3) NULL COMMENT '最近一次发布租约心跳时间UTC',
  `fencing_token` BIGINT NOT NULL DEFAULT 0 COMMENT '发布栅栏令牌',
  `row_version` BIGINT NOT NULL DEFAULT 0 COMMENT '行乐观锁版本',
  `error_message` VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  `published_at` DATETIME(3) NULL COMMENT '确认发布时间UTC',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_outbox_event` (`tenant_id`, `event_id`),
  KEY `idx_rag_outbox_due` (`status`, `next_retry_at`, `lease_until`, `id`),
  KEY `idx_rag_outbox_task` (`tenant_id`, `task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG事务Outbox';

-- -----------------------------------------------------------------------------
-- 五、检索策略、Agent绑定与真实检索/引用留痕
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `rag_retrieval_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `profile_id` VARCHAR(64) NOT NULL COMMENT '检索策略业务ID',
  `profile_name` VARCHAR(128) NOT NULL COMMENT '策略名称',
  `dense_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用Dense召回',
  `sparse_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用Sparse召回',
  `fusion_strategy` VARCHAR(32) NOT NULL DEFAULT 'rrf' COMMENT 'none/rrf/weighted',
  `dense_weight` DECIMAL(8,6) NOT NULL DEFAULT 1.000000 COMMENT 'Dense融合权重',
  `sparse_weight` DECIMAL(8,6) NOT NULL DEFAULT 1.000000 COMMENT 'Sparse融合权重',
  `dense_top_k` INT NOT NULL DEFAULT 40 COMMENT 'Dense候选数',
  `sparse_top_k` INT NOT NULL DEFAULT 40 COMMENT 'Sparse候选数',
  `fusion_top_k` INT NOT NULL DEFAULT 40 COMMENT '融合后候选数',
  `rerank_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用重排',
  `rerank_top_k` INT NOT NULL DEFAULT 10 COMMENT '重排输出数',
  `final_top_k` INT NOT NULL DEFAULT 6 COMMENT '最终上下文切片数',
  `neighbor_window` INT NOT NULL DEFAULT 0 COMMENT '相邻切片扩展窗口',
  `max_context_tokens` INT NOT NULL DEFAULT 4096 COMMENT '检索上下文Token预算',
  `score_threshold` DECIMAL(12,9) NULL COMMENT '最终分数阈值',
  `query_rewrite_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用查询改写',
  `deduplicate_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用内容去重',
  `config_json` JSON NULL COMMENT '版本化扩展配置',
  `revision` BIGINT NOT NULL DEFAULT 1 COMMENT '配置乐观锁版本',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_profile_tenant_id` (`tenant_id`, `profile_id`),
  KEY `idx_rag_profile_tenant_status` (`tenant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG检索策略';

CREATE TABLE IF NOT EXISTS `rag_agent_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `binding_id` VARCHAR(64) NOT NULL COMMENT '绑定业务ID',
  `target_type` VARCHAR(32) NOT NULL DEFAULT 'agent' COMMENT 'agent/workflow/workflow_node',
  `target_id` VARCHAR(64) NOT NULL COMMENT 'Agent、工作流或节点业务ID',
  `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库业务ID',
  `profile_id` VARCHAR(64) NOT NULL COMMENT '检索策略业务ID',
  `priority` INT NOT NULL DEFAULT 0 COMMENT '多个知识库检索优先级',
  `required` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '检索失败时是否阻断Agent运行',
  `max_tokens` INT NOT NULL DEFAULT 4096 COMMENT '本绑定最大上下文Token预算',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `revision` BIGINT NOT NULL DEFAULT 1 COMMENT '绑定乐观锁版本',
  `metadata` JSON NULL COMMENT '扩展元数据',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_agent_binding_id` (`tenant_id`, `binding_id`),
  UNIQUE KEY `uk_rag_target_kb` (`tenant_id`, `target_type`, `target_id`, `kb_id`),
  KEY `idx_rag_target_active` (`tenant_id`, `target_type`, `target_id`, `status`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent与知识库检索策略绑定';

CREATE TABLE IF NOT EXISTS `rag_retrieval_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `retrieval_id` VARCHAR(64) NOT NULL COMMENT '检索调用业务ID',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `user_id` VARCHAR(64) NOT NULL COMMENT '发起用户ID',
  `session_id` VARCHAR(64) NULL COMMENT '会话ID',
  `run_id` VARCHAR(64) NULL COMMENT 'Agent运行ID',
  `agent_id` VARCHAR(64) NULL COMMENT 'Agent业务ID',
  `profile_id` VARCHAR(64) NOT NULL COMMENT '检索策略ID',
  `profile_revision` BIGINT NOT NULL COMMENT '执行时策略版本',
  `query_hash` CHAR(64) NOT NULL COMMENT '规范化查询SHA-256',
  `query_text` TEXT NULL COMMENT '可按租户策略关闭保存的查询正文',
  `dense_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '本次是否启用Dense',
  `sparse_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '本次是否启用Sparse',
  `rerank_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '本次是否启用重排',
  `dense_candidate_count` INT NOT NULL DEFAULT 0 COMMENT 'Dense候选数',
  `sparse_candidate_count` INT NOT NULL DEFAULT 0 COMMENT 'Sparse候选数',
  `fusion_candidate_count` INT NOT NULL DEFAULT 0 COMMENT '融合候选数',
  `final_count` INT NOT NULL DEFAULT 0 COMMENT '最终引用数',
  `embedding_ms` BIGINT NULL COMMENT '查询向量耗时毫秒',
  `dense_ms` BIGINT NULL COMMENT 'Dense检索耗时毫秒',
  `sparse_ms` BIGINT NULL COMMENT 'Sparse检索耗时毫秒',
  `fusion_ms` BIGINT NULL COMMENT '融合耗时毫秒',
  `rerank_ms` BIGINT NULL COMMENT '重排耗时毫秒',
  `assemble_ms` BIGINT NULL COMMENT '上下文组装耗时毫秒',
  `total_ms` BIGINT NOT NULL COMMENT '检索总耗时毫秒',
  `status` VARCHAR(32) NOT NULL COMMENT 'success/empty/failed/cancelled',
  `error_code` VARCHAR(64) NULL COMMENT '稳定错误码',
  `error_message` VARCHAR(1000) NULL COMMENT '脱敏错误摘要',
  `trace_id` VARCHAR(64) NULL COMMENT '链路追踪ID',
  `request_snapshot` JSON NOT NULL COMMENT '不含密钥的实际检索参数快照',
  `stage_metrics` JSON NULL COMMENT '各组件候选与资源指标',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_retrieval_id` (`tenant_id`, `retrieval_id`),
  KEY `idx_rag_retrieval_session` (`tenant_id`, `session_id`, `create_time`),
  KEY `idx_rag_retrieval_profile` (`tenant_id`, `profile_id`, `create_time`),
  KEY `idx_rag_retrieval_trace` (`trace_id`),
  KEY `idx_rag_retrieval_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG检索调用与消融评测留痕';

CREATE TABLE IF NOT EXISTS `rag_retrieval_citation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户业务ID',
  `retrieval_id` VARCHAR(64) NOT NULL COMMENT '检索调用业务ID',
  `citation_id` VARCHAR(64) NOT NULL COMMENT '引用业务ID',
  `rank_no` INT NOT NULL COMMENT '最终引用排序，从1开始',
  `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库业务ID',
  `document_id` VARCHAR(64) NOT NULL COMMENT '文档业务ID',
  `document_version` INT NOT NULL COMMENT '文档版本号',
  `generation` BIGINT NOT NULL COMMENT '索代引代',
  `chunk_id` VARCHAR(64) NOT NULL COMMENT '切片业务ID',
  `vector_point_id` VARCHAR(128) NULL COMMENT 'Qdrant Point ID',
  `dense_score` DECIMAL(20,12) NULL COMMENT 'Dense原始分数',
  `sparse_score` DECIMAL(20,12) NULL COMMENT 'Sparse原始分数',
  `fusion_score` DECIMAL(20,12) NULL COMMENT '融合分数',
  `rerank_score` DECIMAL(20,12) NULL COMMENT '重排分数',
  `page_from` INT NULL COMMENT '引用起始页码',
  `page_to` INT NULL COMMENT '引用结束页码',
  `section_path` VARCHAR(1000) NULL COMMENT '引用标题路径',
  `content_hash` CHAR(64) NOT NULL COMMENT '引用内容SHA-256',
  `content_snapshot` MEDIUMTEXT NULL COMMENT '可按保留策略清理的引用文本快照',
  `metadata` JSON NULL COMMENT '组件排名等扩展留痕',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_citation_id` (`tenant_id`, `citation_id`),
  UNIQUE KEY `uk_rag_citation_rank` (`tenant_id`, `retrieval_id`, `rank_no`),
  KEY `idx_rag_citation_chunk` (`tenant_id`, `chunk_id`, `create_time`),
  KEY `idx_rag_citation_document` (`tenant_id`, `document_id`, `document_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG最终引用及各阶段分数';

-- 删除迁移专用过程；业务表和业务数据不删除。
DROP PROCEDURE IF EXISTS `sp_rag_add_column_20260718`;
DROP PROCEDURE IF EXISTS `sp_rag_add_index_20260718`;
DROP PROCEDURE IF EXISTS `sp_rag_drop_index_20260718`;
DROP PROCEDURE IF EXISTS `sp_rag_make_not_null_20260718`;

-- -----------------------------------------------------------------------------
-- 六、上线步骤（人工执行）
-- -----------------------------------------------------------------------------
-- 1. 记录应用 Git commit、MySQL 版本、本文件 SHA-256，并备份三张历史 RAG 表的结构和数据。
-- 2. 先单独执行第一节审计 SELECT；任一 issue_count 非 0 时停止上线并完成人工数据治理。
-- 3. 在低峰期执行完整文件。三张历史占位表虽预期为空，ALTER TABLE 仍可能获取 metadata lock，
--    执行前检查 information_schema.innodb_trx 和 performance_schema.metadata_locks。
-- 4. 执行下方验证 SQL，确认缺失列/索引为 0、空租户为 0、新表共 7 张。
-- 5. 先部署兼容旧结构且双写 version/task/outbox 的应用，再启用摄取消费者；最后启用检索流量。
-- 6. 观察任务积压、DLT、租约过期、Outbox 延迟和 Qdrant generation 一致性后再扩大流量。

-- -----------------------------------------------------------------------------
-- 七、验证 SQL（预期结果写在注释中）
-- -----------------------------------------------------------------------------

-- 预期 7。
SELECT COUNT(*) AS rag_new_table_count
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
      'rag_document_version', 'rag_ingest_task', 'rag_outbox', 'rag_retrieval_profile',
      'rag_agent_binding', 'rag_retrieval_record', 'rag_retrieval_citation'
  );

-- 预期 0。
SELECT COUNT(*) AS nullable_tenant_table_count
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('rag_knowledge_base', 'rag_document', 'rag_chunk')
  AND COLUMN_NAME = 'tenant_id'
  AND IS_NULLABLE = 'YES';

-- 预期 0；验证关键新增列均存在。
SELECT 28 - COUNT(*) AS missing_critical_column_count
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND (TABLE_NAME, COLUMN_NAME) IN (
      ('rag_knowledge_base', 'current_generation'),
      ('rag_knowledge_base', 'retrieval_profile_id'),
      ('rag_knowledge_base', 'revision'),
      ('rag_document', 'document_version'),
      ('rag_document', 'active_generation'),
      ('rag_document', 'active_version_id'),
      ('rag_document', 'target_generation'),
      ('rag_document', 'revision'),
      ('rag_chunk', 'document_version'),
      ('rag_chunk', 'version_id'),
      ('rag_chunk', 'generation'),
      ('rag_chunk', 'parent_chunk_id'),
      ('rag_chunk', 'previous_chunk_id'),
      ('rag_chunk', 'next_chunk_id'),
      ('rag_chunk', 'content_hash'),
      ('rag_chunk', 'vector_point_id'),
      ('rag_chunk', 'revision'),
      ('rag_document_version', 'chunker_version'),
      ('rag_document_version', 'embedding_model_revision'),
      ('rag_document_version', 'row_version'),
      ('rag_ingest_task', 'lease_until'),
      ('rag_ingest_task', 'fencing_token'),
      ('rag_ingest_task', 'checkpoint'),
      ('rag_ingest_task', 'cancel_reason'),
      ('rag_outbox', 'max_attempts'),
      ('rag_outbox', 'heartbeat_at'),
      ('rag_outbox', 'row_version'),
      ('rag_outbox', 'published_at')
  );

-- 预期 4；验证三类资源联合唯一键与 generation 唯一键。
SELECT COUNT(DISTINCT CONCAT(TABLE_NAME, '.', INDEX_NAME)) AS tenant_unique_index_count
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND INDEX_NAME IN (
      'uk_rag_kb_tenant_id', 'uk_rag_document_tenant_id',
      'uk_rag_chunk_tenant_id', 'uk_rag_chunk_doc_generation_idx'
  )
  AND NON_UNIQUE = 0;

-- 预期三行 issue_count 都为 0。
SELECT 'rag_knowledge_base' AS table_name, COUNT(*) AS issue_count
FROM rag_knowledge_base WHERE tenant_id IS NULL OR TRIM(tenant_id) = ''
UNION ALL
SELECT 'rag_document', COUNT(*) FROM rag_document WHERE tenant_id IS NULL OR TRIM(tenant_id) = ''
UNION ALL
SELECT 'rag_chunk', COUNT(*) FROM rag_chunk WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';

-- -----------------------------------------------------------------------------
-- 八、人工回滚说明
-- -----------------------------------------------------------------------------
-- MySQL DDL 不支持本文件级原子回滚，生产回滚采用“应用先回退、结构向前兼容”的方式：
-- 1. 立即关闭 RAG 摄取/Outbox/检索开关，等待正在执行的 Worker 释放租约；禁止直接删除 Qdrant 集合。
-- 2. 回退应用到旧版本。新增列和 7 张新表不会影响旧代码，保留它们用于审计和后续恢复。
-- 3. 若旧代码依赖 kb_id/document_id/chunk_id 的全局唯一性，在确认跨租户没有重复后，使用一份新的、
--    经评审的前向迁移重新添加历史唯一索引；不要在应急窗口修改或反向执行本文件。
-- 4. 只有在完成数据导出、确认没有新应用读写且经过变更审批后，才可另建清理迁移移除新增结构。
--    本文件刻意不提供删除业务表的回滚语句，避免误删文档版本、任务账本、检索记录和引用证据。
