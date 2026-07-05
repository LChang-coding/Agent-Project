package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

/**
 * MCP 创建请求。
 */
@Data
public class McpCreateRequestDTO {

    /**
     * MCP 名称。
     */
    private String mcpName;

    /**
     * MCP 描述。
     */
    private String description;

    /**
     * 可见范围：private/tenant_public。
     */
    private String visibility;

    /**
     * 版本号。
     */
    private String version;

    /**
     * 传输类型：http/sse/stdio/local。
     */
    private String transportType;

    /**
     * 远程地址。
     */
    private String endpoint;

    /**
     * 管理员本地命令。
     */
    private String command;

    /**
     * 启动参数或远程默认参数。
     */
    private String args;

    /**
     * 环境变量 JSON。
     */
    private String env;
}
