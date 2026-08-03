package cn.bugstack.ai.api.dto.workflow;

import lombok.Builder;
import lombok.Data;

/** 智能工作流运行受理快照。 */
@Data
@Builder
public class IntelligentWorkflowRunResponseDTO {
    private String runId;
    private String workflowId;
    private Integer workflowVersion;
    private String status;
    private String currentNodeId;
    private String traceId;
    private Integer maxSteps;
    private Long tokenBudget;
}
