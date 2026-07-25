package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 摄取任务账本持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagIngestTaskPO extends BasePO {
    /** 摄取任务业务 ID。 */
    private String taskId;
    /** 同一摄取请求的幂等键。 */
    private String taskKey;
    /** 任务所属租户。 */
    private String tenantId;
    /** 目标知识库。 */
    private String knowledgeBaseId;
    /** 目标逻辑文档。 */
    private String documentId;
    /** 目标不可变文档版本。 */
    private String versionId;
    /** 文档内版本号快照。 */
    private Integer documentVersion;
    /** 隔离旧任务写入的目标代次。 */
    private Long generation;
    /** 操作类型：ingest/rebuild/delete。 */
    private String operation;
    /** 当前解析、分块、向量写入或收口阶段。 */
    private String stage;
    /** pending/running/retry/cancel_requested/终态。 */
    private String status;
    /** 已领取执行次数。 */
    private Integer attemptCount;
    /** 允许的最大执行次数。 */
    private Integer maxAttempts;
    /** 可重试失败后的再次领取时间。 */
    private LocalDateTime nextRetryAt;
    /** 当前 Worker 实例 ID。 */
    private String leaseOwner;
    /** 当前租约到期时间。 */
    private LocalDateTime leaseUntil;
    /** Worker 最近心跳时间。 */
    private LocalDateTime heartbeatAt;
    /** 每次领取递增的围栏令牌。 */
    private Long fencingToken;
    /** 行级乐观锁版本。 */
    private Long rowVersion;
    /** 可恢复阶段进度 JSON。 */
    private String checkpoint;
    /** 用户或系统取消原因。 */
    private String cancelReason;
    /** 首次请求取消时间。 */
    private LocalDateTime cancelRequestedAt;
    /** 清理完成并进入取消终态时间。 */
    private LocalDateTime cancelledAt;
    /** 稳定机器可读错误码。 */
    private String errorCode;
    /** 受限的人类可读错误摘要。 */
    private String errorMessage;
    /** 贯穿摄取链路的 traceId。 */
    private String traceId;
    /** 首次实际执行时间。 */
    private LocalDateTime startedAt;
    /** 成功、失败或取消完成时间。 */
    private LocalDateTime finishedAt;
}
