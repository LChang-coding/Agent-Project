package cn.bugstack.ai.api.dto.agent;

import lombok.Data;

import java.util.List;

@Data
public class AgentToolPermissionDTO {
    private String toolCode;
    private String toolName;
    private String toolType;
    private String description;
    private String mode;
    private Integer timeoutSeconds;
    private String timeoutDecision;
    private List<String> suggestions;
    private Long revision;
}
