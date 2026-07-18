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
    private String tenantId;
    private String profileId;
    private String profileName;
    private Integer denseEnabled;
    private Integer sparseEnabled;
    private String fusionStrategy;
    private BigDecimal denseWeight;
    private BigDecimal sparseWeight;
    private Integer denseTopK;
    private Integer sparseTopK;
    private Integer fusionTopK;
    private Integer rerankEnabled;
    private Integer rerankTopK;
    private Integer finalTopK;
    private Integer neighborWindow;
    private Integer maxContextTokens;
    private BigDecimal scoreThreshold;
    private Integer queryRewriteEnabled;
    private Integer deduplicateEnabled;
    private String configJson;
    private Long revision;
    private String status;
}
