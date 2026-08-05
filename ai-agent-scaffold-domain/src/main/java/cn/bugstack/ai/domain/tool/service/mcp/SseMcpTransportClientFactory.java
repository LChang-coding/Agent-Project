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
 * 负责用 SSE 方式连上远程 MCP 服务器，是「远程工具」这条接入路径的建连实现。
 *
 * <p>所属层次：领域层的传输适配实现，注册为 Spring 组件后由 {@code McpProtocolClientSupport} 按传输类型索引使用。</p>
 *
 * <p>它解决的具体麻烦：用户在界面上填的是一整条地址（可能带路径和查询串），
 * 而 MCP 客户端要求「主机地址」和「SSE 路径」分开传。这里就是那道拆分与兜底逻辑。</p>
 *
 * <p>它向下调用什么：MCP 官方客户端的 SSE 传输实现，也就是真的对外发 HTTP 请求。</p>
 *
 * <p>它不负责什么：不发 initialize、不拉工具清单、不调用工具（那些在协议客户端里）；
 * 不判断这个用户有没有权限配置 MCP（在发布服务里判）；不做结果裁剪和审计。</p>
 */
@Component
public class SseMcpTransportClientFactory implements McpTransportClientFactory {

    /**
     * 没有显式配置超时时使用的秒数。
     *
     * <p>为什么必须有默认值：外部 MCP 服务器不受我们控制，可能连上了就一直不回包。
     * 没有超时的话，调用线程会被永久占住，并发一多就把线程池耗尽，进而拖垮整个对话服务。</p>
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * 声明本工厂只处理 sse 这一种传输方式。
     *
     * <p>只在启动建索引时调用一次。不区分大小写，避免用户填 SSE 就匹配不上。</p>
     */
    @Override
    public boolean supports(String transportType) {
        // 忽略大小写比较，保证 sse / SSE / Sse 都能被识别。
        return "sse".equalsIgnoreCase(transportType);
    }

    /**
     * 在不真正建连的情况下检查地址能不能用。
   *
     * <p>做法是直接走一遍地址拆分逻辑：拆得动就算合法，拆不动会抛业务异常。
     * 这样校验和建连用的是同一套规则，不会出现「校验通过但建连时又失败」的错位。</p>
     *
     * <p>用于创建 MCP 时的前置校验，把非法地址挡在入库之前。</p>
     */
    @Override
    public void validate(McpConnectionConfigEntity configuration) {
        // 复用拆分逻辑做校验，结果丢弃；能拆开就说明地址结构可用。
        endpointParts(configuration == null ? null : configuration.getEndpoint());
    }

    /**
     * 按配置创建一个连向远程 MCP 服务器的同步客户端。
     *
     * <p>数据流：完整地址 → 拆成主机部分和 SSE 路径 → 确定超时 → 构建 SSE 传输 → 包成同步客户端返回。</p>
     *
     * <p>同一个超时值被同时用在三处（建连、请求、初始化），因为这三步都可能卡住，
     * 只保护其中一处等于没保护。</p>
     *
     * <p>返回的客户端还没初始化，也没有连接复用：调用方必须自己发 initialize，并在用完后关闭，
     * 否则 HTTP 连接会泄漏。地址为空会抛业务异常。</p>
     */
    @Override
    public McpSyncClient create(McpConnectionConfigEntity configuration) {
        // 先把用户填的整条地址拆成客户端要求的两段，拆不动就直接失败，不带着残缺配置去建连。
        EndpointParts parts = endpointParts(configuration == null ? null : configuration.getEndpoint());
        // 确定本次连接的超时时长，配置缺失时套用默认值，保证永远有上限。
        Duration timeout = Duration.ofSeconds(timeoutSeconds(configuration));
  // 组装 SSE 传输：主机地址决定连哪台机器，SSE 路径决定订阅哪个事件流，连接超时防止建连阶段卡死。
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(parts.baseUri())
                .sseEndpoint(parts.sseEndpoint())
                .connectTimeout(timeout)
                .build();
        // 包装成同步客户端，并把请求超时和初始化超时也一并设上，三个阶段都不允许无限等待。
        return McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
    }

    /**
     * 把用户填的一整条地址拆成「主机部分」和「SSE 路径（含查询串）」两段。
     *
     * <p>数据流：完整地址 → 判空 → 解析成 URI → 取协议和主机拼出主机部分
     * → 取路径（为空时兜底成 /sse）→ 原样接回查询串 → 返回两段结果。</p>
     *
     * <p>两个容错点很关键：路径为空时补成 /sse，是因为多数 MCP 服务器就用这个约定路径，
     * 用户只填主机名也能连上；查询串必须原样带回，因为很多服务把访问令牌放在查询参数里，
     * 丢了就会变成 401，而这类信息也正因此不适合打进日志。</p>
   *
     * <p>地址为空直接抛业务异常，不做任何猜测。</p>
     */
    private EndpointParts endpointParts(String endpoint) {
        // 地址是这条路径唯一的必填项，缺了就没有任何可猜的余地，立刻失败。
        if (endpoint == null || endpoint.isBlank()) {
            // 明确的错误码便于上层统一提示用户去补地址。
            throw new AppException("TOOL_MCP_ENDPOINT_EMPTY", "MCP endpoint 不能为空");
        }
      // 交给标准库解析，顺带完成基本的格式检查。
        URI uri = URI.create(endpoint);
        // 主机部分只保留协议和权限段（域名/IP + 端口），这是 MCP 客户端要求的第一个参数。
        String baseUri = uri.getScheme() + "://" + uri.getAuthority();
        // 路径部分用原始（未解码）形式，避免把已编码的字符再解一遍导致签名或令牌失效；用户没填路径时按惯例兜底成 /sse。
        StringBuilder sseEndpoint = new StringBuilder(uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/sse" : uri.getRawPath());
        // 查询串若存在必须原样接回：很多 MCP 服务把鉴权令牌放在这里，丢掉就直接连不上。
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            // 用问号拼回去，保持与用户填写时完全一致的形态。
            sseEndpoint.append('?').append(uri.getRawQuery());
        }
  // 返回拆好的两段，供建连使用。
        return new EndpointParts(baseUri, sseEndpoint.toString());
    }

 /**
     * 算出本次要用的超时秒数，保证结果一定是个正数。
     *
     * <p>配置为空、为 null 或小于 1 秒都视为没配好，统一套用默认 30 秒。
     * 为什么不允许小于 1：0 或负数在部分客户端里意味着「永不超时」，那正是最危险的情况。</p>
     */
    private int timeoutSeconds(McpConnectionConfigEntity configuration) {
        // 三种异常情况一并兜底成默认值，确保永远不会出现无超时的连接。
        return configuration == null || configuration.getTimeoutSeconds() == null || configuration.getTimeoutSeconds() < 1
                ? DEFAULT_TIMEOUT_SECONDS : configuration.getTimeoutSeconds();
    }

    /**
     * 地址拆分结果：主机部分 + SSE 路径。
     *
     * <p>用 record 是因为它只是两个字符串的临时载体，用完即弃，不参与任何业务判断。
     * 注意 sseEndpoint 里可能含有鉴权查询串，属于敏感内容，不应打印到日志。</p>
     */
    private record EndpointParts(String baseUri, String sseEndpoint) {
    }
}
