package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import cn.bugstack.ai.domain.tool.service.mcp.StdioMcpTransportClientFactory;
import cn.bugstack.ai.test.tool.support.FakeStdioMcpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * stdio MCP 协议端到端测试。
 * <p>通过真实子进程验证 initialize、tools/list 和 tools/call 的标准输入输出通信。</p>
 */
public class McpProtocolClientStdioIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证 stdio MCP 可以拉取工具并调用；无参数；无返回值。
     */
    @Test
    public void shouldListAndCallToolThroughStdioProcess() throws Exception {
        McpProtocolClientSupport support = new McpProtocolClientSupport(List.of(new StdioMcpTransportClientFactory()));
        String args = objectMapper.writeValueAsString(List.of(
                "-cp", System.getProperty("java.class.path"), FakeStdioMcpServer.class.getName()));
        McpVersionEntity version = McpVersionEntity.builder()
                .transportType("stdio")
                .command(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .args(args)
                .env("{}")
                .build();

        String schemaJson = support.listToolsSchema(version);
        ToolCatalogEntity tool = ToolCatalogEntity.builder()
                .toolType("mcp")
                .toolId("mcp_stdio_test")
                .transportType("stdio")
                .command(version.getCommand())
                .args(version.getArgs())
                .env(version.getEnv())
                .build();
        String result = support.callTool(tool, "echo", Map.of("content", "hello stdio"));

        Assert.assertEquals(List.of("echo"), support.toolNames(schemaJson));
        Assert.assertEquals("hello stdio", result);
    }
}
