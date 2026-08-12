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
    /** 管理页展示名称，不持久化到权限表。 */
    private String toolName;
    /** platform、mcp 或 skill，用于管理页分组。 */
    private String toolType;
    /** 工具用途摘要，不包含连接密钥和内部路径。 */
    private String description;
    private String mode;
    private Integer timeoutSeconds;
    private String timeoutDecision;
    private List<String> suggestions;
    private Long revision;
    private String updatedBy;
}
