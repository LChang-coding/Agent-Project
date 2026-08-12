package cn.bugstack.ai.api.dto.agent;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class ToolApprovalEventDTO {
    private Long sequence; private String approvalId; private String parentAgentId; private String toolCode;
    private String parentRunId; private String sourceRunId; private String parentSessionId; private String traceId;
    private Map<String,Object> requestedInput; private List<String> suggestions; private String status;
    private List<String> allowedSubAgentIds; private String timeoutDecision; private String expiresAt; private Long revision;
}
