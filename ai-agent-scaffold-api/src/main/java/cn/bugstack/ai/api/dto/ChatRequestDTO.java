package cn.bugstack.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String workflowId;
    private Integer workflowVersion;
    private String modelCode;
    private String userId;
    private String sessionId;
    private String requestedRunId;
    private String message;
    private List<String> attachmentIds;

}
