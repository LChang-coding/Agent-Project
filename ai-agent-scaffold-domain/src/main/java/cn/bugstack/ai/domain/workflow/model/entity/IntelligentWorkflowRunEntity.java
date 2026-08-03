package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** chat_run 的智能工作流状态扩展；根取消和会话归属仍由 chat_run 裁决。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntelligentWorkflowRunEntity {
    private String tenantId;
    private String userId;
    private String runId;
    private String workflowId;
    private Integer workflowVersion;
    private String definitionHash;
    private String traceId;
    private String status;
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
