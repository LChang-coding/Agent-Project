package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户 Agent 状态覆盖实体。
 */
@Data
@Builder
public class AgentTenantOverrideEntity {
    private String tenantId;
    private String agentId;
    private String status;
    private String reason;
    private String updatedBy;
    private Long revision;
    private LocalDateTime disabledAt;
}
