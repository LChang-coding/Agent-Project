package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.tool.service.ToolGateway;
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * stdio MCP 工具网关测试。
 */
public class ToolGatewayStdioTest {

    /**
     * 验证网关将 stdio MCP 调用委托给统一协议客户端；无参数；无返回值。
     */
    @Test
    public void shouldInvokeStdioMcpThroughProtocolClient() {
        RecordingMcpProtocolClientSupport protocolClientSupport = new RecordingMcpProtocolClientSupport();
        ToolGateway toolGateway = new ToolGateway(mock(IToolRepository.class), null, protocolClientSupport,
                mock(cn.bugstack.ai.domain.run.service.RunExecutionGate.class));
        ToolCatalogEntity tool = ToolCatalogEntity.builder()
                .toolType("mcp")
                .toolId("mcp_stdio_001")
                .toolName("测试 stdio MCP")
                .version("1.0.0")
                .transportType("stdio")
                .command("java")
                .args("[]")
                .env("{}")
                .build();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("toolName", "echo");
        input.put("argumentsJson", "{\"content\":\"hello\"}");

        Map<String, Object> result = toolGateway.invoke(tool, input, ToolInvokeContextEntity.builder()
                .tenantId("tenant_001")
                .userId("user_001")
                .sessionId("session_001")
                .traceId("trace_001")
                .build());

        Assert.assertEquals(true, result.get("success"));
        Assert.assertEquals("stdio-result", result.get("result"));
        Assert.assertEquals("echo", protocolClientSupport.toolName);
        Assert.assertEquals("hello", protocolClientSupport.arguments.get("content"));
    }

    /**
     * 记录 MCP 协议调用的测试替身。
     */
    private static class RecordingMcpProtocolClientSupport extends McpProtocolClientSupport {

        private String toolName;
        private Map<String, Object> arguments;

        /**
         * 创建测试协议客户端；无参数；返回测试实例。
         */
        private RecordingMcpProtocolClientSupport() {
            super(List.of());
        }

        /**
         * 记录工具调用；参数是工具、工具名和参数；返回固定结果。
         */
        @Override
        public String callTool(ToolCatalogEntity tool, String toolName, Map<String, Object> arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
            return "stdio-result";
        }
    }
}
