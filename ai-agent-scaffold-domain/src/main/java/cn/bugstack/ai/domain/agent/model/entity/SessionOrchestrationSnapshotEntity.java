package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 会话级 Multi-Agent 只读快照；前端刷新后可由它完整恢复运行面板。 */
@Data
@Builder
public class SessionOrchestrationSnapshotEntity {
    private String sessionId;
    private String version;
    private boolean active;
    private boolean inputLocked;
    private String phase;
    private String currentRunId;
    private List<Run> runs;
    private List<Approval> approvals;

    @Data
    @Builder
    public static class Run {
        private String parentRunId;
        private String parentAgentId;
        private String phase;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
        private List<Task> tasks;
    }

    @Data
    @Builder
    public static class Task {
        private String taskId;
        private String childAgentId;
        private String childSessionId;
        private String childRunId;
        private String childRunTraceId;
        private String instruction;
        private String traceId;
        private String status;
        private String callbackStatus;
        private Integer attempt;
        private String resultSummary;
        private String fullContext;
        private String errorCode;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
    }

    @Data
    @Builder
    public static class Approval {
        private String approvalId;
        private String parentRunId;
        private String parentAgentId;
        private String toolCode;
        private String expiresAt;
        private Long revision;
    }
}
