package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

/**
 * 工作流下拉选项。
 */
@Data
public class WorkflowOptionDTO {

    /**
     * 选项值。
     */
    private String value;

    /**
     * 展示标签。
     */
    private String label;

    /**
     * 选项描述。
     */
    private String description;

    /**
     * 选项类型。
     */
    private String type;

    /**
     * 选项状态。
     */
    private String status;
}
