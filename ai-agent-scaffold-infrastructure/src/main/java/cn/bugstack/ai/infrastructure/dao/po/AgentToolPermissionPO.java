package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class AgentToolPermissionPO {
    private String tenantId;
    private String agentId;
    private String toolCode;
    private String mode;
    private Integer timeoutSeconds;
    private String timeoutDecision;
    private String suggestionsJson;
    private Long revision;
    private String updatedBy;
}
