package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * Dense 与 Sparse 候选融合方式。
 */
public enum RagFusionStrategy {
    NONE,
    RRF,
    WEIGHTED
}
