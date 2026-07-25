package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * RAG 检索策略持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagRetrievalProfilePO extends BasePO {
    /** 策略所属租户。 */
    private String tenantId;
    /** 策略业务 ID。 */
    private String profileId;
    /** 管理台展示名称。 */
    private String profileName;
    /** 是否启用 Dense 召回。 */
    private Integer denseEnabled;
    /** 是否启用 Sparse 召回。 */
    private Integer sparseEnabled;
    /** 融合算法，如 RRF 或 weighted。 */
    private String fusionStrategy;
    /** Dense 通道融合权重。 */
    private BigDecimal denseWeight;
    /** Sparse 通道融合权重。 */
    private BigDecimal sparseWeight;
    /** Dense 初召回数量。 */
    private Integer denseTopK;
    /** Sparse 初召回数量。 */
    private Integer sparseTopK;
    /** 融合后保留数量。 */
    private Integer fusionTopK;
    /** 是否启用重排。 */
    private Integer rerankEnabled;
    /** 送入重排模型的候选数。 */
    private Integer rerankTopK;
    /** 最终返回分块数。 */
    private Integer finalTopK;
    /** 命中分块前后补齐邻居数量。 */
    private Integer neighborWindow;
    /** 最终上下文 Token 硬预算。 */
    private Integer maxContextTokens;
    /** 最低有效得分。 */
    private BigDecimal scoreThreshold;
    /** 是否启用查询改写。 */
    private Integer queryRewriteEnabled;
    /** 是否按内容或文档去重。 */
    private Integer deduplicateEnabled;
    /** 非结构化扩展策略 JSON。 */
    private String configJson;
    /** 乐观并发修订号。 */
    private Long revision;
    /** active/disabled 状态。 */
    private String status;
}
