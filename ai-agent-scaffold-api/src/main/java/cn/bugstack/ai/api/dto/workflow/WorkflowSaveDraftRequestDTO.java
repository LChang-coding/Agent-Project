package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

/**
 * 保存工作流草稿请求。
 */
@Data
public class WorkflowSaveDraftRequestDTO {

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

    /**
     * 画布图结构。
     */
    private WorkflowGraphDTO graph;
}
