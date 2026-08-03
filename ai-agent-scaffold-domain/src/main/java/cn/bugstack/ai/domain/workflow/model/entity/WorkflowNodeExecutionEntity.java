package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次逻辑节点执行的可追溯快照。 */
@Data
@Builder
public class WorkflowNodeExecutionEntity {
    private String tenantId;
    private String runId;
    private String nodeExecutionId;
    private String nodeId;
    private Integer executionIndex;
    private Integer attempt;
    private String status;
    private String displayOutput;
    private String outputJson;
    private Long promptTokens;
    private Long candidateTokens;
    private Long totalTokens;
    private String errorCode;
    private String errorMessage;
    private String traceId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
