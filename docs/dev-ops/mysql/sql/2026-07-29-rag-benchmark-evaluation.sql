-- RAG PDF/DOCX 细粒度评测账本。
-- 仅保存实验身份、原始明细、汇总和失败证据引用；不保存数据库或模型服务凭据。

CREATE TABLE IF NOT EXISTS `rag_benchmark_dataset` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dataset_id` VARCHAR(120) NOT NULL COMMENT '稳定数据集业务ID',
  `dataset_name` VARCHAR(255) NOT NULL COMMENT '数据集名称',
  `manifest_sha256` CHAR(64) NOT NULL COMMENT '数据集manifest摘要',
  `tree_sha256` CHAR(64) NOT NULL COMMENT '本地数据树摘要',
  `source_url` VARCHAR(1000) NOT NULL COMMENT '公开来源地址',
  `source_revision` VARCHAR(255) NOT NULL COMMENT '来源版本',
  `license_code` VARCHAR(255) NOT NULL COMMENT '许可证摘要',
  `paired_document_count` INT NOT NULL COMMENT '同源文档对数',
  `query_count` INT NOT NULL COMMENT '问题数',
  `qrel_count` INT NOT NULL COMMENT 'qrels行数',
  `manifest_snapshot` JSON NOT NULL COMMENT '不含凭据的完整manifest',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_benchmark_dataset_id` (`dataset_id`),
  UNIQUE KEY `uk_rag_benchmark_dataset_manifest` (`manifest_sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG评测数据集版本';

CREATE TABLE IF NOT EXISTS `rag_benchmark_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `run_id` VARCHAR(120) NOT NULL COMMENT '评测运行业务ID',
  `dataset_id` VARCHAR(120) NOT NULL COMMENT '数据集业务ID',
  `format` VARCHAR(16) NOT NULL COMMENT 'PDF/DOCX',
  `preprocessing_strategy` VARCHAR(64) NOT NULL COMMENT '预处理消融策略',
  `preprocessing_revision` VARCHAR(128) NOT NULL COMMENT '预处理算法版本',
  `git_commit` CHAR(40) NOT NULL COMMENT '应用Git提交',
  `config_sha256` CHAR(64) NOT NULL COMMENT '脱敏运行配置摘要',
  `run_manifest_sha256` CHAR(64) NOT NULL COMMENT '运行manifest摘要',
  `artifact_root` VARCHAR(1000) NOT NULL COMMENT '项目内结果相对目录',
  `status` VARCHAR(32) NOT NULL COMMENT 'running/completed/failed/cancelled',
  `expected_document_count` INT NOT NULL,
  `completed_document_count` INT NOT NULL DEFAULT 0,
  `expected_query_result_count` INT NOT NULL,
  `completed_query_result_count` INT NOT NULL DEFAULT 0,
  `error_count` INT NOT NULL DEFAULT 0,
  `degraded_count` INT NOT NULL DEFAULT 0,
  `empty_result_count` INT NOT NULL DEFAULT 0,
  `started_at` DATETIME(3) NOT NULL,
  `finished_at` DATETIME(3) NULL,
  `run_manifest` JSON NOT NULL COMMENT '不含凭据的运行manifest',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_benchmark_run_id` (`run_id`),
  KEY `idx_rag_benchmark_run_identity`
    (`dataset_id`, `format`, `preprocessing_strategy`, `config_sha256`),
  KEY `idx_rag_benchmark_run_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG评测运行';

CREATE TABLE IF NOT EXISTS `rag_benchmark_document_result` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `run_id` VARCHAR(120) NOT NULL,
  `source_document_id` VARCHAR(120) NOT NULL,
  `format_document_id` VARCHAR(160) NOT NULL,
  `complexity` VARCHAR(16) NOT NULL COMMENT 'SIMPLE/MEDIUM/COMPLEX',
  `document_sha256` CHAR(64) NOT NULL,
  `task_id` VARCHAR(120) NULL,
  `status` VARCHAR(32) NOT NULL COMMENT 'completed/failed/degraded/cancelled',
  `parser_name` VARCHAR(128) NULL,
  `parser_revision` VARCHAR(255) NULL,
  `quality_disposition` VARCHAR(64) NULL,
  `quality_score` DECIMAL(12,8) NULL,
  `page_count` INT NOT NULL DEFAULT 0,
  `character_count` INT NOT NULL DEFAULT 0,
  `chunk_count` INT NOT NULL DEFAULT 0,
  `error_code` VARCHAR(128) NULL,
  `stage_metrics` JSON NOT NULL COMMENT '解析、清洗、切块、向量和写入阶段指标',
  `artifact_refs` JSON NOT NULL COMMENT 'IR、quality、chunk manifest等相对路径和hash',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_benchmark_document` (`run_id`, `format_document_id`),
  KEY `idx_rag_benchmark_document_slice` (`run_id`, `complexity`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG逐文档摄取评测明细';

CREATE TABLE IF NOT EXISTS `rag_benchmark_query_result` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `run_id` VARCHAR(120) NOT NULL,
  `retrieval_variant` VARCHAR(64) NOT NULL COMMENT 'dense/sparse/hybrid_rrf/hybrid_rrf_rerank',
  `query_id` VARCHAR(120) NOT NULL,
  `query_sha256` CHAR(64) NOT NULL,
  `retrieval_id` VARCHAR(120) NULL,
  `gold_document_ids` JSON NOT NULL,
  `ranked_document_ids` JSON NOT NULL,
  `recall_at_1` DECIMAL(12,8) NOT NULL,
  `recall_at_5` DECIMAL(12,8) NOT NULL,
  `recall_at_10` DECIMAL(12,8) NOT NULL,
  `mrr_at_10` DECIMAL(12,8) NOT NULL,
  `ndcg_at_10` DECIMAL(12,8) NOT NULL,
  `map_at_10` DECIMAL(12,8) NOT NULL,
  `precision_at_10` DECIMAL(12,8) NOT NULL,
  `elapsed_ms` BIGINT NOT NULL,
  `degraded` TINYINT(1) NOT NULL DEFAULT 0,
  `degradation_reasons` JSON NOT NULL,
  `error_code` VARCHAR(128) NULL,
  `stage_timings_ms` JSON NOT NULL,
  `candidate_counts` JSON NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_benchmark_query` (`run_id`, `retrieval_variant`, `query_id`),
  KEY `idx_rag_benchmark_query_failure` (`run_id`, `error_code`, `degraded`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG逐问题检索评测明细';

CREATE TABLE IF NOT EXISTS `rag_benchmark_aggregate` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `run_id` VARCHAR(120) NOT NULL,
  `retrieval_variant` VARCHAR(64) NOT NULL,
  `slice_type` VARCHAR(32) NOT NULL COMMENT 'ALL/COMPLEXITY/FORMAT等',
  `slice_value` VARCHAR(128) NOT NULL,
  `sample_count` INT NOT NULL,
  `quality_metrics` JSON NOT NULL,
  `latency_metrics` JSON NOT NULL,
  `candidate_metrics` JSON NOT NULL,
  `metrics_sha256` CHAR(64) NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_benchmark_aggregate`
    (`run_id`, `retrieval_variant`, `slice_type`, `slice_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG评测汇总指标';

CREATE TABLE IF NOT EXISTS `rag_benchmark_failure_case` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `failure_id` VARCHAR(160) NOT NULL,
  `run_id` VARCHAR(120) NOT NULL,
  `query_id` VARCHAR(120) NULL,
  `source_document_id` VARCHAR(120) NULL,
  `retrieval_variant` VARCHAR(64) NULL,
  `first_failure_stage` VARCHAR(64) NOT NULL,
  `failure_category` VARCHAR(128) NOT NULL,
  `direct_facts` JSON NOT NULL,
  `causal_analysis` TEXT NOT NULL,
  `alternative_explanation` TEXT NULL,
  `evidence_relative_path` VARCHAR(1000) NOT NULL,
  `evidence_sha256` CHAR(64) NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_benchmark_failure_id` (`failure_id`),
  KEY `idx_rag_benchmark_failure_run` (`run_id`, `failure_category`, `first_failure_stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG召回失败案例与证据引用';

-- 结构门禁：结果应为 0。
SELECT COUNT(*) AS missing_table_count
FROM (
  SELECT 'rag_benchmark_dataset' AS table_name UNION ALL
  SELECT 'rag_benchmark_run' UNION ALL
  SELECT 'rag_benchmark_document_result' UNION ALL
  SELECT 'rag_benchmark_query_result' UNION ALL
  SELECT 'rag_benchmark_aggregate' UNION ALL
  SELECT 'rag_benchmark_failure_case'
) expected
LEFT JOIN information_schema.tables actual
  ON actual.table_schema = DATABASE() AND actual.table_name = expected.table_name
WHERE actual.table_name IS NULL;
