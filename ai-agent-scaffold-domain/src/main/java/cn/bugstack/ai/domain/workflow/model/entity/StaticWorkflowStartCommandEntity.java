package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 普通 DAG 工作流启动命令；身份字段只能由可信请求上下文填写。 */
@Data
@Builder
public class StaticWorkflowStartCommandEntity {
    private String tenantId;
    private String userId;
    private String roleCode;
    private String workflowId;
    private Integer workflowVersion;
    private String modelCode;
    private String sessionId;
    private String message;
    private String requestedRunId;
    private List<String> attachmentIds;
}
