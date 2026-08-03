package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

import java.util.List;

/** 智能工作流运行启动请求。 */
@Data
public class IntelligentWorkflowRunStartRequestDTO {
    private String workflowId;
    private Integer workflowVersion;
    private String modelCode;
    private String sessionId;
    private String message;
    private String requestedRunId;
    private List<String> attachmentIds;
}
