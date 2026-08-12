package cn.bugstack.ai.domain.agent.model.entity;

import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次临时子 Agent 执行的权威任务记录。 */
@Data
@Builder
public class SubagentTaskEntity {
    private String tenantId;
    private String userId;
    private String parentRunId;
    private String parentSessionId;
    private String parentAgentId;
    private String taskId;
    private String childAgentId;
    private String childSessionId;
    private String instruction;
    private String functionCallId;
    private String traceId;
    private SubagentTaskStatus status;
    private Integer attempt;
    private Long fencingToken;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private String resultText;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime acknowledgedAt;
    private String callbackStatus;
    private String callbackOwner;
    private LocalDateTime callbackClaimedAt;
    private Integer callbackAttempt;
}
