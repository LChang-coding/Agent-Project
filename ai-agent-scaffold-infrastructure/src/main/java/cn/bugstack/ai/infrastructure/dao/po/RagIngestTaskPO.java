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
    private String taskId;
    private String taskKey;
    private String tenantId;
    private String knowledgeBaseId;
    private String documentId;
    private String versionId;
    private Integer documentVersion;
    private Long generation;
    private String operation;
    private String stage;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextRetryAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    private Long fencingToken;
    private Long rowVersion;
    private String checkpoint;
    private String cancelReason;
    private LocalDateTime cancelRequestedAt;
    private LocalDateTime cancelledAt;
    private String errorCode;
    private String errorMessage;
    private String traceId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
