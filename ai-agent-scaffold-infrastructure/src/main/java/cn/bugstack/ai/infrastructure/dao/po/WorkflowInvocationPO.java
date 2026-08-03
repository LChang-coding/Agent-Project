package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** workflow_invocation 表持久化对象。 */
@Data
public class WorkflowInvocationPO {
    private String tenantId;
    private String runId;
    private String invocationId;
    private String idempotencyKey;
    private String nodeExecutionId;
    private String invocationType;
    private String replayClass;
    private String status;
    private String downstreamRequestId;
    private String traceId;
    private LocalDateTime startedAt;
}
