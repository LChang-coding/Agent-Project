package cn.bugstack.ai.domain.tool.service.mcp;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import cn.bugstack.ai.types.exception.AppException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * SSE MCP 传输客户端工厂。
 * <p>负责拆分 SSE 地址并创建远程 MCP 同步客户端。</p>
 */
@Component
public class SseMcpTransportClientFactory implements McpTransportClientFactory {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * 判断是否支持 SSE；参数是传输类型；返回是否支持。
     */
    @Override
    public boolean supports(String transportType) {
        return "sse".equalsIgnoreCase(transportType);
    }

    /**
     * 校验 SSE 配置；参数是连接配置；无返回值。
     */
    @Override
    public void validate(McpConnectionConfigEntity configuration) {
        endpointParts(configuration == null ? null : configuration.getEndpoint());
    }

    /**
     * 创建 SSE MCP 客户端；参数是连接配置；返回同步客户端。
     */
    @Override
    public McpSyncClient create(McpConnectionConfigEntity configuration) {
        EndpointParts parts = endpointParts(configuration == null ? null : configuration.getEndpoint());
        Duration timeout = Duration.ofSeconds(timeoutSeconds(configuration));
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(parts.baseUri())
                .sseEndpoint(parts.sseEndpoint())
                .connectTimeout(timeout)
                .build();
        return McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
    }

    /**
     * 解析 SSE endpoint；参数是完整 endpoint；返回主地址和 SSE 路径。
     */
    private EndpointParts endpointParts(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new AppException("TOOL_MCP_ENDPOINT_EMPTY", "MCP endpoint 不能为空");
        }
        URI uri = URI.create(endpoint);
        String baseUri = uri.getScheme() + "://" + uri.getAuthority();
        StringBuilder sseEndpoint = new StringBuilder(uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/sse" : uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            sseEndpoint.append('?').append(uri.getRawQuery());
        }
        return new EndpointParts(baseUri, sseEndpoint.toString());
    }

    /**
     * 获取超时秒数；参数是连接配置；返回大于零的超时秒数。
     */
    private int timeoutSeconds(McpConnectionConfigEntity configuration) {
        return configuration == null || configuration.getTimeoutSeconds() == null || configuration.getTimeoutSeconds() < 1
                ? DEFAULT_TIMEOUT_SECONDS : configuration.getTimeoutSeconds();
    }

    /**
     * SSE 地址拆分结果。
     */
    private record EndpointParts(String baseUri, String sseEndpoint) {
    }
}
