package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** agent_orchestration_outbox 表持久化对象。 */
@Data
public class AgentOrchestrationOutboxPO {
    private String tenantId;
    private String eventId;
    private String eventType;
    private String aggregateId;
    private String partitionKey;
    private String payload;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private Long fencingToken;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime createdAt;
}
