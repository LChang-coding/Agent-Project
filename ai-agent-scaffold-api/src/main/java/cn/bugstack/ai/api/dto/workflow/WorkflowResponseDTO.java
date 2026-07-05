package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

/**
 * 工作流摘要响应。
 */
@Data
public class WorkflowResponseDTO {

    /**
     * 工作流ID。
     */
    private String workflowId;

    /**
     * 工作流名称。
     */
    private String workflowName;

    /**
     * 工作流描述。
     */
    private String description;

    /**
     * 可见范围。
     */
    private String visibility;

    /**
     * 工作流状态。
     */
    private String status;

    /**
     * 默认模型编码。
     */
    private String defaultModelCode;

    /**
     * 当前草稿版本。
     */
    private Integer currentVersion;

    /**
     * 当前发布版本。
     */
    private Integer publishedVersion;
}
