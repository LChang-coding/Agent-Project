package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

/** 节点执行后的唯一路由裁决。 */
@Data
@Builder
public class WorkflowRouteDecisionEntity {

    /** 路由裁决所属租户。 */
    private String tenantId;

    /** 路由裁决所属工作流运行。 */
    private String runId;

    /** 路由裁决唯一标识。 */
    private String routeDecisionId;

    /** 产生本次裁决的逻辑节点执行。 */
    private String nodeExecutionId;

    /** 本次路由的源节点。 */
    private String sourceNodeId;

    /** 命中的目标节点；结束运行时可以为空。 */
    private String targetNodeId;

    /** 命中的工作流边；直接结束运行时可以为空。 */
    private String edgeId;

    /** 最终生效的路由策略，例如工具选择、条件、默认或失败路由。 */
    private String strategy;

    /** 说明该策略为什么选择当前目标。 */
    private String reason;

    /** 是否要求运行在当前节点后结束。 */
    private Boolean terminal;

    /** 与根工作流运行一致的跟踪标识。 */
    private String traceId;
}
