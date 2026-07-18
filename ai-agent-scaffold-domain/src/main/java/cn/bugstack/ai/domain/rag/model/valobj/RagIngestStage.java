package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 文档摄取流水线阶段。
 */
public enum RagIngestStage {
    RECEIVED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    VERIFYING,
    COMPLETED;

    /** 判断目标阶段是否是当前阶段或紧邻的下一阶段。 */
    public boolean canAdvanceTo(RagIngestStage target) {
        return target != null && (target.ordinal() == ordinal() || target.ordinal() == ordinal() + 1);
    }
}
