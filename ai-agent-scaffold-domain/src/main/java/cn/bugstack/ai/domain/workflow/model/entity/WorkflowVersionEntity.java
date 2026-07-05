package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工作流版本实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowVersionEntity {

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
     * 版本状态。
     */
    private String versionStatus;

    /**
     * 默认模型编码。
     */
    private String defaultModelCode;

    /**
     * 画布图结构。
     */
    private WorkflowGraphEntity graph;

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
}
