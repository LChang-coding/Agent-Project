package cn.bugstack.ai.api.dto.rag;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** RAG 检索策略响应。 */
@Data
@Builder
public class RagRetrievalProfileResponseDTO {
    private String profileId;
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
    private Long revision;
}
