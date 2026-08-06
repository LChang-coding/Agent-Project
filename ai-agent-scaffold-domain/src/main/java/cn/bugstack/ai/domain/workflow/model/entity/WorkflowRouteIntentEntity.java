package cn.bugstack.ai.domain.workflow.model.entity;

import cn.bugstack.ai.domain.workflow.model.valobj.WorkflowRouteIntentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 模型已选择但尚未被运行时消费的工作流路由意图。 */
@Data
@Builder
public class WorkflowRouteIntentEntity {
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
    private WorkflowRouteIntentStatus status;
    private String traceId;
    private LocalDateTime consumedAt;
}
