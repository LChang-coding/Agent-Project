package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建一个 MCP 工具的请求参数，涵盖远程 SSE 和本机 stdio 两种接入方式。
 *
 * <p>所属层次：工具领域的实体（入参命令对象），不落库。</p>
 *
 * <p>谁消费它：{@code ToolPublishService#createMcp}。它会依次校验可见范围、传输类型是否允许该角色配置、
 * args/env 是不是合法 JSON、stdio 命令能不能组装成进程参数，全部通过后才同时建出 MCP 定义和首个未测试版本。</p>
 *
 * <p>创建出来的 MCP 一定是「草稿 + 未测试」：必须先调测试接口真的连上去拉到远程工具清单，才允许发布。
 * 否则模型会拿到一个连不通的工具，反复重试并把错误文案带进后续提示词。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpCreateCommandEntity {

    /** 操作人身份；决定工具归属哪个租户和所有者，也决定他有没有资格配置 stdio 这种能起本机进程的传输方式。 */
    private ToolUserContextEntity context;
    /** MCP 在租户内的展示名称；会拼进给模型看的函数描述，模型靠它判断这个工具集是做什么的。 */
    private String mcpName;
  /** 用途说明；同样进入模型可见的描述文本，写清楚能显著减少模型误调。 */
    private String description;
    /** 可见范围（private 或 tenant_public）；为空按私有处理，填租户公开时会校验操作人是不是 owner/admin。 */
    private String visibility;
    /** 首个版本号；为空用默认 1.0.0。版本创建后其连接配置即冻结，改配置要靠加新版本。 */
    private String version;
    /** 传输类型（sse 或 stdio）；sse 是远程连接谁都能配，stdio 会在服务器起子进程，只有租户管理员能配。 */
    private String transportType;
    /**
     * SSE 服务地址；stdio 模式下留空。
     * 这是外部系统入口，地址里若带鉴权查询串则属于敏感信息，不应打进日志。
     */
    private String endpoint;
    /**
     * stdio 模式要执行的命令；SSE 模式下留空。
     * 它会真的在服务器上启动进程，等于把执行本机命令的能力交出去，所以传输类型校验里对它卡得最严。
     */
    private String command;
    /** stdio 的命令行参数，必须是 JSON 字符串数组文本；创建时会解析一遍，格式不对直接拒绝入库。 */
    private String args;
    /**
     * stdio 的环境变量，必须是 JSON 字符串对象文本；外部服务的密钥通常放这里。
     * 它是高敏感字段：只在启动子进程时注入，不写日志、不回传给模型，也不进入工具描述。
     */
    private String env;
}
