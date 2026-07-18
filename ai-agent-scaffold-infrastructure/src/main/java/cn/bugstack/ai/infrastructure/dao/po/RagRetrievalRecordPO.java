package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** RAG 检索调用审计持久化对象。 */
@Data
@Builder
@EqualsAndHashCode(callSuper = true)
public class RagRetrievalRecordPO extends BasePO {
    private String retrievalId;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String runId;
    private String agentId;
    private String profileId;
    private Long profileRevision;
    private String queryHash;
    private String queryText;
    private Integer denseEnabled;
    private Integer sparseEnabled;
    private Integer rerankEnabled;
    private Integer denseCandidateCount;
    private Integer sparseCandidateCount;
    private Integer fusionCandidateCount;
    private Integer finalCount;
    private Long embeddingMs;
    private Long denseMs;
    private Long sparseMs;
    private Long fusionMs;
    private Long rerankMs;
    private Long assembleMs;
    private Long totalMs;
    private String status;
    private String errorCode;
    private String errorMessage;
    private String traceId;
    private String requestSnapshot;
    private String stageMetrics;
}
