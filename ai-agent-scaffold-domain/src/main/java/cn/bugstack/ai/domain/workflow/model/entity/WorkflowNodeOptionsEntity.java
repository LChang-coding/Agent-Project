package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工作流节点选项实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowNodeOptionsEntity {

    /**
     * 节点类型选项。
     */
    private List<WorkflowOptionEntity> nodeTypes;

    /**
     * 模型选项。
     */
    private List<WorkflowOptionEntity> models;

    /**
     * MCP 选项。
     */
    private List<WorkflowOptionEntity> mcpServers;

    /**
     * Skill 选项。
     */
    private List<WorkflowOptionEntity> skills;
}
