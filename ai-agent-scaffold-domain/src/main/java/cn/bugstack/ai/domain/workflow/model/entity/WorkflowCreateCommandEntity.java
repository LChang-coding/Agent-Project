package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.Data;

/**
 * 创建工作流命令。
 */
@Data
public class WorkflowCreateCommandEntity {

    /**
     * 租户业务ID。
     */
    private String tenantId;

    /**
     * 用户业务ID。
     */
    private String userId;

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
     * 可见范围。
     */
    private String visibility;
}
