package cn.bugstack.ai.domain.workflow.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流 MCP 工具实体。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowMcpToolEntity {

    /**
     * MCP 业务ID。
     */
    private String mcpId;

    /**
     * MCP 名称。
     */
    private String mcpName;

    /**
     * 传输类型：sse/stdio/local。
     */
    private String transportType;

    /**
     * 远程端点。
     */
    private String endpoint;

    /**
     * 本地命令。
     */
    private String command;

    /**
     * 命令参数。
     */
    private String args;

    /**
     * 环境变量。
     */
    private String env;
}
