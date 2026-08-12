package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ToolApprovalRequestPO {
    private Long sequence; private String approvalId; private String tenantId; private String userId;
    private String parentRunId; private String sourceRunId; private String parentSessionId; private String parentAgentId;
    private String functionCallId; private String toolCode; private String requestedInputJson; private String amendedInputJson;
    private String allowedSubAgentIdsJson; private String suggestionsJson; private String status; private String timeoutDecision;
    private LocalDateTime expiresAt; private String decision; private String comment; private Long revision; private String traceId;
}
