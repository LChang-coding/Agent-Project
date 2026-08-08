package cn.bugstack.ai.domain.rag.model.valobj;

/**
 * 稠密召回与稀疏召回候选的融合策略。
 * <p>检索配置负责校验模式与融合策略的一致性，检索服务使用该策略计算统一融合分数。</p>
 */
public enum RagFusionStrategy {

    /**
     * 不融合，直接沿用单路召回的排名。
     *
     * <p>只允许在 DENSE 或 SPARSE 单通道模式下使用；混合模式配成 NONE 会被配置校验拒绝，
   * 否则两路候选无从比较，等于随机丢弃一半结果。</p>
     */
    NONE,

    /**
     * 按排名倒数融合（Reciprocal Rank Fusion）：只看候选在各路里排第几，不看原始分数。
     *
   * <p>适合两路分数量纲完全不同、没法直接相加的情况，稳定但无法体现「某路特别有信心」。</p>
     */
    RRF,

    /**
     * 按分数加权融合：先把两路分数归一化，再用 denseWeight / sparseWeight 加权求和。
     *
     * <p>使用它时两个权重至少有一个要大于 0，否则所有候选融合分都是 0，排名会退化成随机。</p>
     */
    WEIGHTED
}
