package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 一次检索启用的召回通道。
 * <p>检索配置根据该模式校验对应候选数，检索服务根据该模式
 * 决定是否生成稠密向量、稀疏向量或执行多路融合。</p>
 */
public enum RagRetrievalMode {

    /** 仅执行稠密语义向量召回；要求 denseTopK 大于 0。 */
    DENSE,

    /** 仅执行稀疏词项召回；要求 sparseTopK 大于 0。 */
    SPARSE,

    /**
     * 同时执行稠密与稀疏召回并融合候选；要求两路 TopK 均大于 0，
     * 融合策略不是 NONE，且两路权重不能同时为 0。
     */
    HYBRID
}
