package cn.bugstack.ai.api.dto.agent;

import lombok.Data;

import java.util.List;

@Data
public class AgentToolPermissionUpdateRequestDTO {
    private String mode;
    private Integer timeoutSeconds;
    private String timeoutDecision;
    private List<String> suggestions;
    private Long expectedRevision;
}
