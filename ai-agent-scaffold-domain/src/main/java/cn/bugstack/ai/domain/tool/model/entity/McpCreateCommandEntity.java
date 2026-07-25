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

    /** 从认证上下文构造的操作者身份。 */
    private ToolUserContextEntity context;
    /** 租户内展示名称。 */
    private String mcpName;
    /** 工具用途说明。 */
    private String description;
    /** private 或 tenant_public。 */
    private String visibility;
    /** 首个不可变版本号。 */
    private String version;
    /** sse 或 stdio。 */
    private String transportType;
    /** SSE 服务地址；Stdio 时为空。 */
    private String endpoint;
    /** Stdio 启动命令；SSE 时为空。 */
    private String command;
    /** Stdio 参数 JSON 数组。 */
    private String args;
    /** Stdio 环境变量 JSON 对象。 */
    private String env;
}
