package cn.bugstack.ai.domain.workflow.model.entity;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工作流 DAG 编译结果。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowDagCompileResultEntity {

    /**
     * 需要装配到运行时的节点 Agent 配置。
     */
    private List<AiAgentConfigTableVO> tables;

    /**
     * DAG 执行计划。
     */
    private WorkflowDagPlanEntity dagPlan;
}
