package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

/** 节点执行后的唯一路由裁决。 */
@Data
@Builder
public class WorkflowRouteDecisionEntity {
    private String tenantId;
    private String runId;
    private String routeDecisionId;
    private String nodeExecutionId;
    private String sourceNodeId;
    private String targetNodeId;
    private String edgeId;
    private String strategy;
    private String reason;
    private Boolean terminal;
    private String traceId;
}
