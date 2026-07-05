package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流下拉选项实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowOptionEntity {

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
