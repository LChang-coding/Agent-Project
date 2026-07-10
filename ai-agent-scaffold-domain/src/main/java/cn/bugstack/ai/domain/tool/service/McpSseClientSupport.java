package cn.bugstack.ai.domain.tool.service;

import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP SSE 客户端兼容适配器。
 * <p>保留旧调用入口，并把协议交互统一委托给支持 SSE 和 stdio 的通用客户端。</p>
 */
@Deprecated
@Component
public class McpSseClientSupport {

    private final McpProtocolClientSupport mcpProtocolClientSupport;

    /**
     * 创建 SSE 兼容适配器；参数是通用 MCP 协议客户端；返回适配器实例。
     */
    public McpSseClientSupport(McpProtocolClientSupport mcpProtocolClientSupport) {
        this.mcpProtocolClientSupport = mcpProtocolClientSupport;
    }

    /**
     * 拉取 MCP 工具 Schema；参数是 MCP 版本；返回 tools/list 快照 JSON。
     */
    public String listToolsSchema(McpVersionEntity version) {
        return mcpProtocolClientSupport.listToolsSchema(version);
    }

    /**
     * 拉取 SSE MCP 工具 Schema；参数是 SSE endpoint；返回 tools/list 快照 JSON。
     */
    public String listToolsSchema(String endpoint) {
        return mcpProtocolClientSupport.listToolsSchema(McpVersionEntity.builder()
                .transportType("sse")
                .endpoint(endpoint)
                .build());
    }

    /**
     * 调用 MCP 工具；参数是工具目录、远程工具名和参数；返回远程工具结果文本。
     */
    public String callTool(ToolCatalogEntity tool, String toolName, Map<String, Object> arguments) {
        return mcpProtocolClientSupport.callTool(tool, toolName, arguments);
    }

    /**
     * 提取可用工具名；参数是 tools/list 快照 JSON；返回工具名列表。
     */
    public List<String> toolNames(String schemaJson) {
        return mcpProtocolClientSupport.toolNames(schemaJson);
    }

    /**
     * 提取工具说明摘要；参数是 tools/list 快照 JSON；返回模型可读摘要。
     */
    public String toolSummary(String schemaJson) {
        List<String> toolNames = toolNames(schemaJson);
        return toolNames.isEmpty()
                ? "当前 MCP 尚未完成测试，未拉取到远程工具清单。"
                : String.join("；", toolNames);
    }
}
