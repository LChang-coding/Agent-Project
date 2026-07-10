package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import cn.bugstack.ai.domain.tool.service.mcp.StdioMcpTransportClientFactory;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

/**
 * stdio MCP 传输客户端工厂测试。
 */
public class StdioMcpTransportClientFactoryTest {

    /**
     * 验证合法 stdio 配置可被解析；无参数；无返回值。
     */
    @Test
    public void shouldAcceptValidStdioConfiguration() {
        StdioMcpTransportClientFactory factory = new StdioMcpTransportClientFactory();

        factory.validate(McpConnectionConfigEntity.builder()
                .transportType("stdio")
                .command("java")
                .args("[\"-version\"]")
                .env("{\"TZ\":\"Asia/Shanghai\"}")
                .build());
    }

    /**
     * 验证 stdio 参数不是字符串数组时拒绝创建；无参数；无返回值。
     */
    @Test
    public void shouldRejectNonArrayStdioArguments() {
        StdioMcpTransportClientFactory factory = new StdioMcpTransportClientFactory();

        try {
            factory.validate(McpConnectionConfigEntity.builder()
                    .transportType("stdio")
                    .command("java")
                    .args("{\"arg\":\"-version\"}")
                    .env("{}")
                    .build());
            Assert.fail("args 不是字符串数组时应抛出异常");
        } catch (AppException e) {
            Assert.assertEquals("TOOL_MCP_STDIO_ARGS_INVALID", e.getCode());
        }
    }
}
