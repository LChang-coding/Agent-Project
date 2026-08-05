package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个 MCP 工具的某一版冻结快照：连接怎么连、远程有哪些工具，在这一版里永远不变。
 *
 * <p>所属层次：工具领域的实体，对应 MCP 版本表。</p>
 *
 * <p>为什么必须冻结：模型这一轮基于「远程有 tool A、tool B」做出的决定，
 * 如果中途有人把配置改成另一台服务器，模型的调用就会打到完全不同的地方去。
 * 把连接参数和工具清单钉死在版本里，才能保证一次对话从头到尾行为一致，出问题也能复现。</p>
 *
 * <p>谁读写它：{@code ToolPublishService} 创建版本、测试后回填 schema 与测试结论、发布时置为激活；
 * {@code McpProtocolClientSupport} 在测试时从它摘出连接参数去建连。</p>
 *
 * <p>它不负责什么：不保存草稿配置（在定义记录里）、不保存调用日志、不做权限校验。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpVersionEntity {

    /** 数据库自增主键；仅库内使用。 */
    private Long id;
    /** 版本所属租户；查询版本时必须带上，防止跨租户读到别人的连接配置。 */
    private String tenantId;
    /** 创建这一版时的所有者快照；即使定义后来转手，也能追溯这一版是谁配的。 */
    private String ownerUserId;
    /** 所属 MCP 的业务编号；和版本号一起构成业务上的唯一定位。 */
    private String mcpId;
    /** 版本记录自身的稳定业务编号；发布时写进定义的激活版本指针。 */
    private String versionId;
    /** 用户可见的版本号（如 1.0.0）；同一个 MCP 下不允许重复，重复创建会直接报错。 */
    private String version;
    /** 冻结的传输类型；调用和测试都按这个值选择客户端工厂，不会因为草稿被改而变化。 */
    private String transportType;
    /** 冻结的 SSE 地址；这一版的所有调用都打到这里，日志中不应原样输出可能内含凭证的查询串。 */
    private String endpoint;
    /** 冻结的 stdio 启动命令；会在服务器起子进程，因此这一版能否被创建早已由角色校验把过关。 */
    private String command;
    /** 冻结的 stdio 参数 JSON；建连时解析成命令行参数数组。 */
    private String args;
    /**
 * 冻结的 stdio 环境变量 JSON；外部服务密钥通常在此。
     * 高敏感：只在启动子进程时注入，不打日志、不回传模型、不出现在模型可见描述里。
     */
    private String env;
    /**
     * 测试时通过标准协议拉回来的远程工具清单 JSON（tools/list 快照）。
     * 它就是「声明给大模型的工具能力」的来源：模型看到的可用远程工具摘要由它生成，
     * 模型没指定工具名时也靠它推断唯一工具。为空表示还没测过，这一版禁止发布。
     */
    private String toolSchemaJson;
    /** 这一版的测试结论（untested/success/failed）；发布接口会强制要求它是 success。 */
    private String testStatus;
    /** 这一版的测试摘要；失败时保存的是异常消息，供配置者据此修正地址或命令。 */
    private String testMessage;
    /** 版本生命周期状态（draft/active）；发布时置为 active，运行目录只关联激活版本。 */
    private String status;
    /** 预留扩展元数据 JSON；当前流程不写。 */
    private String metadata;
}
