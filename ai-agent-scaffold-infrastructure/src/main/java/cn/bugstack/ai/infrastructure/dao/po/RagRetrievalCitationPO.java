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
    /** 引用所属租户。 */
    private String tenantId;
    /** 产生引用的检索 ID。 */
    private String retrievalId;
    /** 返回给调用方的引用 ID。 */
    private String citationId;
    /** 最终结果中的顺序。 */
    private Integer rankNo;
    /** 来源知识库。 */
    private String knowledgeBaseId;
    /** 来源逻辑文档。 */
    private String documentId;
    /** 来源文档版本号。 */
    private Integer documentVersion;
    /** 来源索引代次。 */
    private Long generation;
    /** 来源分块 ID。 */
    private String chunkId;
    /** Qdrant 点 ID，便于追查向量记录。 */
    private String vectorPointId;
    /** Dense 原始得分。 */
    private BigDecimal denseScore;
    /** Sparse 原始得分。 */
    private BigDecimal sparseScore;
    /** 多路融合得分。 */
    private BigDecimal fusionScore;
    /** 重排模型得分。 */
    private BigDecimal rerankScore;
    /** 引用起始页。 */
    private Integer pageFrom;
    /** 引用结束页。 */
    private Integer pageTo;
    /** 文档结构路径。 */
    private String sectionPath;
    /** 引用正文摘要，用于完整性核验。 */
    private String contentHash;
    /** 返回当时的受限正文快照。 */
    private String contentSnapshot;
    /** 引用扩展元数据。 */
    private String metadata;
}
