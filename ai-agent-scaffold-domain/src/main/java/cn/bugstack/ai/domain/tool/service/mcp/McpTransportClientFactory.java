package cn.bugstack.ai.domain.tool.service.mcp;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import io.modelcontextprotocol.client.McpSyncClient;

/**
 * MCP 传输客户端工厂。
 * <p>负责按传输协议创建已配置的 MCP 同步客户端。</p>
 */
public interface McpTransportClientFactory {

    /**
     * 判断是否支持传输类型；参数是传输类型；返回是否支持。
     */
    boolean supports(String transportType);

    /**
     * 校验传输连接配置；参数是 MCP 连接配置；无返回值。
     */
    void validate(McpConnectionConfigEntity configuration);

    /**
     * 创建 MCP 同步客户端；参数是 MCP 连接配置；返回客户端。
     */
    McpSyncClient create(McpConnectionConfigEntity configuration);
}
