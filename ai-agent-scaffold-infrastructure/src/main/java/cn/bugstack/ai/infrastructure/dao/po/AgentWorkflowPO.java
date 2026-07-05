package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工作流主表持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AgentWorkflowPO extends BasePO {

    /**
     * 租户业务ID。
     */
    private String tenantId;

    /**
     * 拥有者用户ID。
     */
    private String ownerUserId;

    /**
     * 可见范围：private/tenant_public。
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
     * 工作流状态：draft/published/disabled/archived。
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

    /**
     * 扩展信息。
     */
    private String metadata;
}
