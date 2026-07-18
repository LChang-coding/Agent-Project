package cn.bugstack.ai.api.dto.rag;

import lombok.Data;

import java.math.BigDecimal;

/** 租户管理员创建或更新 RAG 检索策略的请求。 */
@Data
public class RagRetrievalProfileRequestDTO {
    private String name;
    private String mode;
    private String fusionStrategy;
    private BigDecimal denseWeight;
    private BigDecimal sparseWeight;
    private Integer denseTopK;
    private Integer sparseTopK;
    private Integer fusionTopK;
    private Boolean rerankEnabled;
    private Integer rerankTopK;
    private Integer finalTopK;
    private Integer neighborWindow;
    private Integer maxContextTokens;
    private BigDecimal scoreThreshold;
    private Boolean queryRewriteEnabled;
    private Boolean deduplicateEnabled;
    private Long expectedRevision;
}
