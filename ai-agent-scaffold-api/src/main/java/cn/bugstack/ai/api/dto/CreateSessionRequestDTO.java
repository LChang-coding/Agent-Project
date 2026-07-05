package cn.bugstack.ai.api.dto;

import lombok.Data;

@Data
public class CreateSessionRequestDTO {

    private String agentId;
    private String workflowId;
    private Integer workflowVersion;
    private String modelCode;

    private String userId;

}
