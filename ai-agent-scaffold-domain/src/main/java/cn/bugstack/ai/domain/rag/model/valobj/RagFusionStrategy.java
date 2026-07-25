package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * Dense 与 Sparse 候选融合方式。
 * <p>NONE 用于单路召回；RRF 按排名融合；WEIGHTED 按归一化分数和配置权重融合。</p>
 */
public enum RagFusionStrategy {
    NONE,
    RRF,
    WEIGHTED
}
