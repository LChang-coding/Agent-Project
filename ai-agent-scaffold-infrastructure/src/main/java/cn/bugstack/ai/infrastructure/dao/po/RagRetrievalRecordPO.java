package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** RAG 检索调用审计持久化对象。 */
@Data
@Builder
@EqualsAndHashCode(callSuper = true)
public class RagRetrievalRecordPO extends BasePO {
    /** 一次检索业务 ID。 */
    private String retrievalId;
    /** 检索所属租户。 */
    private String tenantId;
    /** 发起检索的可信用户。 */
    private String userId;
    /** 来源会话。 */
    private String sessionId;
    /** 来源运行。 */
    private String runId;
    /** 使用检索结果的 Agent。 */
    private String agentId;
    /** 本次冻结的策略 ID。 */
    private String profileId;
    /** 本次冻结的策略修订号。 */
    private Long profileRevision;
    /** 查询文本摘要，用于聚合且避免泄露。 */
    private String queryHash;
    /** 受审计策略允许保存的查询文本。 */
    private String queryText;
    /** 本次是否执行 Dense 通道。 */
    private Integer denseEnabled;
    /** 本次是否执行 Sparse 通道。 */
    private Integer sparseEnabled;
    /** 本次是否执行重排。 */
    private Integer rerankEnabled;
    /** Dense 通道候选数。 */
    private Integer denseCandidateCount;
    /** Sparse 通道候选数。 */
    private Integer sparseCandidateCount;
    /** 融合后候选数。 */
    private Integer fusionCandidateCount;
    /** 最终返回数量。 */
    private Integer finalCount;
    /** 查询向量生成耗时。 */
    private Long embeddingMs;
    /** Dense 检索耗时。 */
    private Long denseMs;
    /** Sparse 检索耗时。 */
    private Long sparseMs;
    /** 融合耗时。 */
    private Long fusionMs;
    /** 重排耗时。 */
    private Long rerankMs;
    /** 邻居补齐和上下文组装耗时。 */
    private Long assembleMs;
    /** 检索端到端总耗时。 */
    private Long totalMs;
    /** success/failed/degraded 状态。 */
    private String status;
    /** 稳定机器可读错误码。 */
    private String errorCode;
    /** 受限错误摘要。 */
    private String errorMessage;
    /** 全链路 traceId。 */
    private String traceId;
    /** 脱敏后的请求配置快照。 */
    private String requestSnapshot;
    /** 各阶段详细指标 JSON。 */
    private String stageMetrics;
}
