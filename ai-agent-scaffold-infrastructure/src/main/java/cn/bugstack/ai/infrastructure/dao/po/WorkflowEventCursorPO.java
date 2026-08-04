package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

/** workflow_run_event_cursor 持久化对象。 */
@Data
public class WorkflowEventCursorPO {
    private Long id;
    private String tenantId;
    private String userId;
    private String runId;
    private String traceId;
    private Long nextSequence;
    private String terminalEventType;
    private Long terminalSequence;
    private Long revision;
}
