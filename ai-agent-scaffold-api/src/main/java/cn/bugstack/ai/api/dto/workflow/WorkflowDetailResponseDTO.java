package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

/**
 * 工作流详情响应。
 */
@Data
public class WorkflowDetailResponseDTO {

    /**
     * 工作流摘要。
     */
    private WorkflowResponseDTO workflow;

    /**
     * 当前版本号。
     */
    private Integer version;

    /**
     * 版本状态：draft/published。
     */
    private String versionStatus;

    /**
     * 画布图结构。
     */
    private WorkflowGraphDTO graph;
}
