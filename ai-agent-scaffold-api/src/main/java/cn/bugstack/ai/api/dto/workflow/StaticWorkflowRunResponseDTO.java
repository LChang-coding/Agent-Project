package cn.bugstack.ai.api.dto.workflow;

import lombok.Builder;
import lombok.Data;

/** 普通 DAG 工作流启动响应；返回根 Run 和根 Trace 供事件续传。 */
@Data
@Builder
public class StaticWorkflowRunResponseDTO {
    private String runId;
    private String sessionId;
    private String workflowId;
    private String status;
    private String traceId;
    private String operationTraceId;
}
