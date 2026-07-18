package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** RAG 最终引用审计持久化对象。 */
@Data
@Builder
@EqualsAndHashCode(callSuper = true)
public class RagRetrievalCitationPO extends BasePO {
    private String tenantId;
    private String retrievalId;
    private String citationId;
    private Integer rankNo;
    private String knowledgeBaseId;
    private String documentId;
    private Integer documentVersion;
    private Long generation;
    private String chunkId;
    private String vectorPointId;
    private BigDecimal denseScore;
    private BigDecimal sparseScore;
    private BigDecimal fusionScore;
    private BigDecimal rerankScore;
    private Integer pageFrom;
    private Integer pageTo;
    private String sectionPath;
    private String contentHash;
    private String contentSnapshot;
    private String metadata;
}
