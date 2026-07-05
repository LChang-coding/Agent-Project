package cn.bugstack.ai.api.dto.workflow;

import lombok.Data;

import java.util.List;

/**
 * 工作流节点选项响应。
 */
@Data
public class WorkflowNodeOptionsResponseDTO {

    /**
     * 节点类型选项。
     */
    private List<WorkflowOptionDTO> nodeTypes;

    /**
     * 模型选项。
     */
    private List<WorkflowOptionDTO> models;

    /**
     * MCP 选项。
     */
    private List<WorkflowOptionDTO> mcpServers;

    /**
     * Skill 选项。
     */
    private List<WorkflowOptionDTO> skills;
}
