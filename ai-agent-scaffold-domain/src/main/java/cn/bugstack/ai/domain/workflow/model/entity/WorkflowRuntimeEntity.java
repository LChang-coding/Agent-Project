package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流运行时实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowRuntimeEntity {

    /**
     * 会话隔离使用的工作流ID。
     */
    private String workflowId;

    /**
     * 实际注册到 Spring 容器的运行时 Agent ID。
     */
    private String runtimeAgentId;

    /**
     * 运行时版本号。
     */
    private Integer version;

    /**
     * 本次有效模型编码。
     */
    private String effectiveModelCode;

    /**
     * DAG 执行计划。
     */
    private WorkflowDagPlanEntity dagPlan;
}
