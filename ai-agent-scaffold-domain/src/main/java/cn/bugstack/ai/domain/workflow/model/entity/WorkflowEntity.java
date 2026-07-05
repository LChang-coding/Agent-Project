package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流主信息实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowEntity {

    /**
     * 租户业务ID。
     */
    private String tenantId;

    /**
     * 拥有者用户ID。
     */
    private String ownerUserId;

    /**
     * 可见范围。
     */
    private String visibility;

    /**
     * 工作流业务ID。
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
