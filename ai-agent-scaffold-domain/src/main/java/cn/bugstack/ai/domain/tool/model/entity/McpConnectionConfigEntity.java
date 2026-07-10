package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 客户端连接配置。
 * <p>统一承载 SSE 和 stdio 建连所需的版本快照字段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpConnectionConfigEntity {

    /**
     * 传输类型：sse/stdio。
     */
    private String transportType;

    /**
     * SSE 服务地址。
     */
    private String endpoint;

    /**
     * stdio 启动命令。
     */
    private String command;

    /**
     * stdio 启动参数 JSON 字符串数组。
     */
    private String args;

    /**
     * stdio 环境变量 JSON 字符串对象。
     */
    private String env;

    /**
     * MCP 请求超时秒数。
     */
    private Integer timeoutSeconds;
}
