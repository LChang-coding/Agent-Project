package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ParentResumeRequestPO {
    private String tenantId;
    private String userId;
    private String parentRunId;
    private String parentSessionId;
    private String parentAgentId;
    private String traceId;
    private String status;
    private Boolean parentReady;
    private String parentDraft;
    private Long requestedVersion;
    private Long processedVersion;
    private Long inboxCursor;
    private String leaseOwner;
    private Long fencingToken;
    private LocalDateTime leaseExpiresAt;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime recoveryNotifiedAt;
}
