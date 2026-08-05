package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.impl;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;

/**
 * 通过 HTTP SSE 连接远程 MCP 工具服务，并在装配期拿到它的工具清单。
 *
 * <p>解决什么问题：工具服务通常独立部署在别的机器上。这里在装配阶段就把连接建好、握手做完，
 * 之后所有工具调用复用同一条连接，避免每次调用重新建连。</p>
 *
 * <p>所属层次：领域层的装配辅料实现。</p>
 *
 * <p>谁会调用它：{@code DefaultMcpClientFactory} 在配置里填了 sse 参数时选中它。</p>
 *
 * <p>它向下调用什么：真实发起 HTTP 连接到配置的工具服务，因此服务不通会导致装配失败。</p>
 *
 * <p>它不负责什么：不做重连、不做熔断。连接在装配后一直保持，运行期断连的表现是工具调用超时。</p>
 */
@Slf4j
@Service
public class SSEToolMcpCreateService implements TooMcpCreateService {

    /**
     * 建立 SSE 连接、完成握手，返回远程服务声明的工具清单。
     *
     * <p>各层职责：
     * 第一层：取出 SSE 配置，准备地址和端点两个变量。
     * 第二层：兼容旧配置——有人把完整 SSE 地址整个填进了 baseUri，这里把它拆成「主机基址 + 端点路径」。
     * 第三层：仍然拆不出端点时，退回 MCP 约定的默认端点 /sse。
     * 第四层：用基址和端点建立 SSE 传输通道，并按配置的超时创建同步客户端。
     * 第五层：同步完成握手并记录结果，然后把客户端包成工具清单返回。</p>
     *
     * <p>数据流：
     * SSE 配置（baseUri + 可选 sseEndpoint + 超时）
     * → 若端点为空则从完整地址中拆出主机基址与端点路径
     * → 端点仍为空则用默认 /sse
     * → 建立 SSE 传输通道
     * → 创建同步客户端并握手
     * → 工具清单
     * → 返回给装配链挂到模型上</p>
     *
     * <p>地址不通、握手超时都会抛异常，阻止发布一个工具不可用的 Agent。</p>
     */
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception {
        // 第一层：取出 SSE 配置段。
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sseConfig = toolMcp.getSse();

        // 保留配置里原始的地址，下面拆分端点时要在它上面做字符串定位。
        String originalBaseUri = sseConfig.getBaseUri();
        // 实际用于建连的主机基址，默认先等于原始地址。
        String baseUri = originalBaseUri;
        // SSE 端点相对路径；配置里可能没写，需要推断。
        String sseEndpoint = sseConfig.getSseEndpoint();

        // 第二层：没单独写端点，说明可能是旧写法把完整地址塞进了 baseUri。
        if (StringUtils.isBlank(sseEndpoint)) {
            // 兼容把完整 SSE URL 填入 baseUri 的旧配置，拆成主机基址和端点路径。
            URL url = new URL(originalBaseUri);

            // 取协议（http/https），用于重新拼出干净的主机基址。
            String protocol = url.getProtocol();
            // 取主机名。
            String host = url.getHost();
            // 取端口；没写端口时为 -1。
            int port = url.getPort();

            // 拼出不带路径的主机基址；没显式端口就不拼端口，避免出现 host:-1 这种非法地址。
            String baseUrl = port == -1 ? protocol + "://" + host : protocol + "://" + host + ":" + port;

            // 在原始地址里定位主机基址，基址之后剩下的就是端点路径。
            int index = originalBaseUri.indexOf(baseUrl);
            // 定位成功才截取端点；失败则保持端点为空，交给下面的默认值兜底。
            if (index != -1) {
                // 截取主机基址之后的部分作为端点路径。
                sseEndpoint = originalBaseUri.substring(index + baseUrl.length());
            }

            // 把建连用的基址换成拆出来的干净基址。
            baseUri = baseUrl;
        }

        // 第三层：仍未解析到路径时使用 MCP SSE 常规端点。
        sseEndpoint = StringUtils.isBlank(sseEndpoint) ? "/sse" : sseEndpoint;

        // 第四层：用基址和端点建立 SSE 传输通道，此时还未真正发起握手。
        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport
                .builder(baseUri)
                .sseEndpoint(sseEndpoint)
                .build();

        // 初始化在装配期同步完成；连接失败阻止发布不可用 Agent。
        McpSyncClient mcpSyncClient = McpClient
                .sync(sseClientTransport)
                .requestTimeout(Duration.ofMillis(sseConfig.getRequestTimeout())).build();
        // 第五层：真正发起握手，拿到服务端能力声明。
        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();

        // 把握手结果记进日志，排查「工具服务版本或能力不符」时用得上。
        log.info("tool sse mcp initialize {}", initialize);

        // 回调持有已初始化客户端，后续工具调用复用同一连接。
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpSyncClient).build()
                .getToolCallbacks();
    }

}
