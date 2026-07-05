package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用上下文实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvokeContextEntity {

    private String tenantId;
    private String userId;
    private String sessionId;
    private String workflowId;
    private String invocationId;
    private String traceId;
}
