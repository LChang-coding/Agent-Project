package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 可持久化、可重放的通用工作流业务事件。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunEventEntity {
    private String tenantId;
    private String userId;
    private String runId;
    private String eventId;
    private Long sequence;
    private String schemaVersion;
    private String eventType;
    private String nodeExecutionId;
    private String nodeId;
    private String payloadJson;
    private String traceId;
    private LocalDateTime occurredAt;
    private LocalDateTime expiresAt;
}
