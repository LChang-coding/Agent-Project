package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 外部调用账本记录；登记成功后才允许执行网络调用。 */
@Data
@Builder
public class WorkflowInvocationEntity {
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
    private LocalDateTime finishedAt;
}
