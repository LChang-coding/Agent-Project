package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import cn.bugstack.ai.domain.tool.service.mcp.StdioMcpTransportClientFactory;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * MCP 协议运行时测试。
 */
public class McpProtocolClientSupportTest {

    /**
     * 验证协议运行时按传输类型路由 stdio；无参数；无返回值。
     */
    @Test
    public void shouldRouteStdioConfigurationToStdioFactory() {
        McpProtocolClientSupport support = new McpProtocolClientSupport(List.of(new StdioMcpTransportClientFactory()));

        support.validate(McpConnectionConfigEntity.builder()
                .transportType("stdio")
                .command("java")
                .args("[]")
                .env("{}")
                .build());
    }

    /**
     * 验证协议运行时拒绝没有工厂的传输类型；无参数；无返回值。
     */
    @Test
    public void shouldRejectUnsupportedTransport() {
        McpProtocolClientSupport support = new McpProtocolClientSupport(List.of(new StdioMcpTransportClientFactory()));

        try {
            support.validate(McpConnectionConfigEntity.builder().transportType("sse").endpoint("http://localhost/sse").build());
            Assert.fail("没有客户端工厂的传输类型应抛出异常");
        } catch (AppException e) {
            Assert.assertEquals("TOOL_MCP_TRANSPORT_UNSUPPORTED", e.getCode());
        }
    }
}
