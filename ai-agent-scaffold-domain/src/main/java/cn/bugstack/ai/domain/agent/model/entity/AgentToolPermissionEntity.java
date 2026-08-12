package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 一个租户对某个主 Agent 工具的执行策略。 */
@Data
@Builder
public class AgentToolPermissionEntity {
    private String tenantId;
    private String agentId;
    private String toolCode;
    private String mode;
    private Integer timeoutSeconds;
    private String timeoutDecision;
    private List<String> suggestions;
    private Long revision;
    private String updatedBy;
}
