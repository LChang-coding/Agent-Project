package cn.bugstack.ai.api.dto.workflow;

import lombok.Builder;
import lombok.Data;

/** workflow-event-v1 对外事件信封。 */
@Data
@Builder
public class WorkflowRunEventResponseDTO {
    private String schemaVersion;
    private String eventId;
    private Long sequence;
    private String runId;
    private String eventType;
    private String nodeExecutionId;
    private String nodeId;
    private String payloadJson;
    private String traceId;
    private String occurredAt;
}
