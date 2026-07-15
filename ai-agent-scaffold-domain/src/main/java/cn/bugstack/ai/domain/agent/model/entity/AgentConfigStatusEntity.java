package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 带租户状态的静态 Agent 配置摘要。
 */
@Data
@Builder
public class AgentConfigStatusEntity {
    private String agentId;
    private String agentName;
    private String agentDesc;
    private String status;
    private Boolean enabled;
    private Long revision;
    private LocalDateTime disabledAt;
}
