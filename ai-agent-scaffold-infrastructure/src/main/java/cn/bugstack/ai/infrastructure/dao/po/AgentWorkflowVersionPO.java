package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工作流版本表持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AgentWorkflowVersionPO extends BasePO {

    /**
     * 租户业务ID。
     */
    private String tenantId;

    /**
     * 工作流业务ID。
     */
    private String workflowId;

    /**
     * 版本号。
     */
    private Integer version;

    /**
     * 版本状态：draft/published/archived。
     */
    private String versionStatus;

    /**
     * 默认模型编码。
     */
    private String defaultModelCode;

    /**
     * 画布 JSON。
     */
    private String graphJson;

    /**
     * 创建者用户ID。
     */
    private String createdBy;

    /**
     * 发布者用户ID。
     */
    private String publishedBy;

    /**
     * 发布时间。
     */
    private LocalDateTime publishedTime;

    /**
     * 扩展信息。
     */
    private String metadata;
}
