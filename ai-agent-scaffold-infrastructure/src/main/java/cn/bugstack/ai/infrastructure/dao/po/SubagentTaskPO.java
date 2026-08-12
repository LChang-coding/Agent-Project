package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** agent_subagent_task 表持久化对象。 */
@Data
public class SubagentTaskPO {
    private String tenantId;
    private String userId;
    private String parentRunId;
    private String parentSessionId;
    private String parentAgentId;
    private String taskId;
    private String childAgentId;
    private String instruction;
    private String functionCallId;
    private String traceId;
    private String status;
    private Integer attempt;
    private Long fencingToken;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private String resultText;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime acknowledgedAt;
}
