package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 智能工作流运行状态行，仅投影事件序号分配所需字段。 */
@Data
public class IntelligentWorkflowRunPO {
    private Long id;
    private String tenantId;
    private String userId;
    private String runId;
    private String traceId;
    private String status;
    private String workflowId;
    private Integer workflowVersion;
    private String definitionHash;
    private String currentNodeId;
    private Long nextSequence;
    private Integer executedSteps;
    private Long usedTokens;
    private Integer maxSteps;
    private Long tokenBudget;
    private String variablesJson;
    private Long revision;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
