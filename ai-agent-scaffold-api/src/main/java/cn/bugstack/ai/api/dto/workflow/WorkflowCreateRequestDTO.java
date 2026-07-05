package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

/**
 * 创建工作流请求。
 */
@Data
public class WorkflowCreateRequestDTO {

    /**
     * 工作流名称。
     */
    private String workflowName;

    /**
     * 工作流描述。
     */
    private String description;

    /**
     * 默认模型编码。
     */
    private String defaultModelCode;

    /**
     * 可见范围：private/tenant_public。
     */
    private String visibility;
}
