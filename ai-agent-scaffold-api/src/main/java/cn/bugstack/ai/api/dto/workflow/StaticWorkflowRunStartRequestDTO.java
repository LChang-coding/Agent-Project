package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

import java.util.List;

/** 普通 DAG 工作流启动请求。 */
@Data
public class StaticWorkflowRunStartRequestDTO {
    private String workflowId;
    private Integer workflowVersion;
    private String modelCode;
    private String sessionId;
    private String message;
    private String requestedRunId;
    private List<String> attachmentIds;
}
