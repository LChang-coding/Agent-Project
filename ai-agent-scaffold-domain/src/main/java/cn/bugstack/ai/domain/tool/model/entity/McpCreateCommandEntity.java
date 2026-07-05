package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 创建命令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpCreateCommandEntity {

    private ToolUserContextEntity context;
    private String mcpName;
    private String description;
    private String visibility;
    private String version;
    private String transportType;
    private String endpoint;
    private String command;
    private String args;
    private String env;
}
