package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流详情实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowDetailEntity {

    /**
     * 工作流主信息。
     */
    private WorkflowEntity workflow;

    /**
     * 当前版本。
     */
    private WorkflowVersionEntity version;
}
