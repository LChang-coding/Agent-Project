package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 启动智能工作流所需的可信身份与客户端输入。 */
@Data
@Builder
public class IntelligentWorkflowStartCommandEntity {
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
