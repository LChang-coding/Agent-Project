package cn.bugstack.ai.domain.tool.service.mcp;

import cn.bugstack.ai.domain.tool.model.entity.McpConnectionConfigEntity;
import io.modelcontextprotocol.client.McpSyncClient;

/**
 * 按传输方式创建 MCP 客户端的工厂契约，把「怎么连上外部工具服务器」这件事从业务逻辑里隔离出去。
 *
 * <p>所属层次：领域层的传输适配契约。目前有两个实现：SSE（连远程 HTTP 服务）和 stdio（在本机起子进程）。</p>
 *
 * <p>谁调用它：{@code McpProtocolClientSupport}。它启动时把所有实现按传输类型索引成一张表，
 * 用的时候按工具版本里冻结的传输类型取对应工厂——找不到就直接报「传输类型不支持」，不猜也不降级。</p>
 *
 * <p>重复注册的后果：索引表按传输类型做键，如果将来出现两个都声称支持 sse 的实现，
 * 后注册的会把先注册的覆盖掉，实际用哪个取决于 Spring 给出的 Bean 顺序，属于难排查的隐患，
 * 所以每种传输类型必须且只能有一个实现。</p>
 *
 * <p>实现者必须遵守的两条：创建出来的客户端要设好超时（外部服务卡住不能把调用线程永久挂死）；
 * 客户端是一次性的，用完由调用方 close，不做连接复用，避免上一次调用的会话状态串到下一次。</p>
 *
 * <p>它不负责什么：不发协议请求、不解析工具清单、不做权限判断，只管把连接建起来。</p>
 */
public interface McpTransportClientFactory {

    /**
     * 声明自己支持哪种传输类型（不区分大小写比较）。
     *
     * <p>只在启动建索引时被调用一次，用来把实现登记到对应的传输类型上。</p>
     */
    boolean supports(String transportType);

    /**
     * 在不真正建连的前提下，检查这份连接配置能不能用。
     *
   * <p>用于创建 MCP 时的前置校验：地址能不能拆、命令是否为空、参数和环境变量是不是合法 JSON。
   * 配置不合法就抛业务异常，把问题挡在入库之前，而不是等到用户对话时才失败。</p>
     */
    void validate(McpConnectionConfigEntity configuration);

    /**
     * 按连接配置创建一个 MCP 同步客户端。
     *
     * <p>返回的客户端还没初始化，调用方需要自己发 initialize；它实现了 AutoCloseable，
     * 必须放在 try-with-resources 里用完即关，否则 SSE 连接或子进程会泄漏。</p>
     *
     * <p>会真的建立外部连接或启动子进程，配置非法时抛业务异常。</p>
     */
    McpSyncClient create(McpConnectionConfigEntity configuration);
}
