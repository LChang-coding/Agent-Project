package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** workflow_run_event 表持久化对象。 */
@Data
public class WorkflowRunEventPO {
    private Long id;
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
