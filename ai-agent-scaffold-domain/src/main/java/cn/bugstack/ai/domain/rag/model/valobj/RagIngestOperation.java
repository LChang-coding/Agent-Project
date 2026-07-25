package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * RAG 后台任务操作类型。
 * <p>INGEST 新增版本，REBUILD 重建索引，DELETE 执行不可逆文档清理。</p>
 */
public enum RagIngestOperation {
    INGEST,
    REBUILD,
    DELETE
}
