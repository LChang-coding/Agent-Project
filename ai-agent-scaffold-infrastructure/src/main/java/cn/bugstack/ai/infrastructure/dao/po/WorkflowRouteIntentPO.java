package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** workflow_route_intent 表持久化对象。 */
@Data
public class WorkflowRouteIntentPO {
    private String tenantId;
    private String userId;
    private String runId;
    private String nodeExecutionId;
    private String workflowId;
    private Integer workflowVersion;
    private String definitionHash;
    private String nodeId;
    private String routeKey;
    private String normalizedRouteKey;
    private String resolvedEdgeId;
    private String resolvedTargetNodeId;
    private String reason;
    private String functionCallId;
    private String source;
    private String status;
    private String traceId;
    private LocalDateTime consumedAt;
}
