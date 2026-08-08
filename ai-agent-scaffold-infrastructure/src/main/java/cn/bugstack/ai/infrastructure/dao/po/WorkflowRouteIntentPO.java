package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** workflow_route_intent 表持久化对象。 */
@Data
public class WorkflowRouteIntentPO {
    /** 意图所属租户。 */
    private String tenantId;
    /** 意图所属用户。 */
    private String userId;
    /** 意图所属运行。 */
    private String runId;
    /** 提交意图的节点执行标识。 */
    private String nodeExecutionId;
    /** 运行采用的工作流。 */
    private String workflowId;
    /** 运行冻结的工作流版本。 */
    private Integer workflowVersion;
    /** 提交意图时的工作流定义摘要。 */
    private String definitionHash;
    /** 提交意图的节点。 */
    private String nodeId;
    /** 模型选择的原始路由键。 */
    private String routeKey;
    /** 按工作流规则规范化后的路由键。 */
    private String normalizedRouteKey;
    /** 规范化路由键匹配的边。 */
    private String resolvedEdgeId;
    /** 匹配边指向的目标节点。 */
    private String resolvedTargetNodeId;
    /** 模型提供的路由理由。 */
    private String reason;
    /** 模型工具调用标识，用于调用幂等。 */
    private String functionCallId;
    /** 意图来源类型。 */
    private String source;
    /** 意图是否仍待运行时消费。 */
    private String status;
    /** 意图所在链路的标识。 */
    private String traceId;
    /** 运行时原子消费意图的时间。 */
    private LocalDateTime consumedAt;
}
