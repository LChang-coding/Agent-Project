package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 事务 Outbox 持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RagOutboxPO extends BasePO {
    private String eventId;
    private String tenantId;
    private String taskId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String topicName;
    private String partitionKey;
    private String payload;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextRetryAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    private Long fencingToken;
    private Long rowVersion;
    private String errorMessage;
    private LocalDateTime publishedAt;
}
