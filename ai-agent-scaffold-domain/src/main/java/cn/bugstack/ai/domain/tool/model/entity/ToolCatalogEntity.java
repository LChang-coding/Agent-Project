package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个「当前用户此刻真的可以调用」的工具条目，把 Skill 和 MCP 两种工具拍平成同一个结构。
 *
 * <p>所属层次：工具领域的实体（运行时只读视图）。它是仓储把定义表和已发布版本表关联后的产物，
 * 里面的地址、命令、schema 全部来自「已发布版本」而不是草稿，所以用户在管理页改了草稿不会影响正在跑的对话。</p>
 *
 * <p>谁产出它：{@code IToolRepository#queryAvailableTools}，查询时已经按租户和可见范围过滤过，
 * 因此拿到这个对象就意味着「这个用户有权调它」，下游不需要再判一次权限。</p>
 *
 * <p>谁消费它：{@code GatewayAdkTool} 用它生成给大模型看的函数名、描述和入参 schema；
 * {@code ToolGateway} 用它决定路由到 Skill 还是 MCP，并取出真正的连接参数去建连。</p>
 *
 * <p>它不负责什么：不含调用参数、不含运行身份，也不含草稿态配置和历史版本。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCatalogEntity {

    /** 工具类型（skill 或 mcp）；工具网关据此二选一路由到不同运行时，取值不在这两者之内会直接抛「类型不支持」。 */
    private String toolType;
    /** 工具稳定业务编号；参与幂等键计算、写入调用审计，也是找不到工具时报错的依据，为空视为工具不存在。 */
    private String toolId;
    /** 工具展示名称；会拼进给模型看的函数描述里，也会作为快照抄进调用日志，方便事后知道当时调的是哪个工具。 */
    private String toolName;
    /** Skill 的租户内编码；优先用它生成模型函数名（比编号可读），MCP 没有这个概念时为空，会退化成用编号。 */
    private String toolCode;
  /** 工具用途说明；直接进入模型的函数描述，模型靠它判断该不该调这个工具，写得含糊会导致模型乱调或不调。 */
    private String description;
    /** 当前发布版本号；调用时按这个版本读包或建连，并写进审计，保证「回放某次调用」时知道用的是哪一版行为。 */
    private String version;
    /** 可见范围（private 或 tenant_public）；查询阶段已按它过滤，这里保留是为了审计和界面展示。 */
    private String visibility;
    /** 私有工具的所有者编号；与可见范围配合解释「为什么这个用户能看到这个工具」，运行时不再重复判权。 */
    private String ownerUserId;
    /** Skill 包来源类型；目前固定是对象存储，预留给将来支持其他来源时分流读取逻辑。 */
    private String sourceType;
    /** Skill 包所在的对象存储桶；调用 Skill 时凭它 + 对象键把 ZIP 取回来，MCP 工具为空。 */
    private String bucket;
    /** Skill 包在存储桶里的对象键；和桶名一起唯一定位那个已发布的 ZIP 文件，MCP 工具为空。 */
    private String objectKey;
    /** MCP 的传输类型（sse/stdio/http）；决定走标准 MCP 协议客户端还是旧的直连 HTTP 兼容路径。 */
    private String transportType;
    /** MCP 服务地址；SSE 建连的目标，stdio 类型时为空。它指向外部系统，日志里可以打但不应带上查询串里的密钥。 */
    private String endpoint;
    /** MCP 的 stdio 启动命令；会在服务器上真的启动一个子进程，所以只允许租户管理员配置，普通成员无权设置。 */
    private String command;
    /** MCP 的 stdio 启动参数（JSON 字符串数组）；作为命令行参数传给子进程，格式不合法会在建连前被拒。 */
    private String args;
    /**
     * MCP 的 stdio 环境变量（JSON 字符串对象）；外部服务的 API Key、Token 一般就放在这里。
     * 它是敏感数据：只在建子进程时注入，绝不能写进日志、不能回传给大模型，也不能放进给模型看的工具描述里，
     * 否则模型可能在回答中把凭证原文吐给用户。
     */
    private String env;
    /**
     * 已发布版本的远程工具清单快照（tools/list 的 JSON）；这是 MCP 测试通过时冻结下来的。
     * 两个用途：一是拼成给模型看的「可用远程工具」摘要，二是当模型没给 toolName 时用来推断唯一工具名。
     * 为空说明这个 MCP 还没测试过，模型会被提示先去测试。
     */
    private String schemaJson;
    /** Fixed model function name; platform tools never derive this from tenant input. */
    private String functionName;
}
