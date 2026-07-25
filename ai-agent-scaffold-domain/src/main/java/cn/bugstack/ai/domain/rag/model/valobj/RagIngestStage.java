package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 文档摄取流水线阶段。
 * <p>摄取依次经过接收、解析、切块、向量化、索引、验证和完成；删除使用独立的向量、
 * 分块、源文件清理阶段。</p>
 */
public enum RagIngestStage {
    RECEIVED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    VERIFYING,
    COMPLETED,
    DELETING_VECTORS,
    DELETING_CHUNKS,
    DELETING_SOURCE;

    /** 判断目标阶段是否是当前阶段或紧邻的下一阶段。 */
    public boolean canAdvanceTo(RagIngestStage target) {
        return target != null && (target.ordinal() == ordinal() || target.ordinal() == ordinal() + 1);
    }
}
