package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 一次需要人工确认的平台工具调用；身份字段全部来自服务端可信上下文。 */
@Data
@Builder
public class ToolApprovalRequestEntity {
    private Long sequence;
    private String approvalId;
    private String tenantId;
    private String userId;
    private String parentRunId;
    private String sourceRunId;
    private String parentSessionId;
    private String parentAgentId;
    private String functionCallId;
    private String toolCode;
    private Map<String, Object> requestedInput;
    private Map<String, Object> amendedInput;
    private List<String> allowedSubAgentIds;
    private List<String> suggestions;
    private String status;
    private String timeoutDecision;
    private LocalDateTime expiresAt;
    private String decision;
    private String comment;
    private Long revision;
    private String traceId;
}
