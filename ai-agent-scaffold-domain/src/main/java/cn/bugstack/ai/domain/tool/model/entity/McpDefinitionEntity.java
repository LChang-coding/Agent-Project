package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一个 MCP 工具的「主记录」：保存可编辑的草稿配置，以及指向当前已发布版本的那几个指针。
 *
 * <p>所属层次：工具领域的实体，对应 MCP 定义表。</p>
 *
 * <p>为什么要分成定义和版本两张表：定义是可以随时改的（改地址、改说明），版本是冻结不变的。
 * 线上对话只认已发布的那个版本，所以有人在管理页把地址改坏了，正在跑的对话也不会受影响。</p>
 *
 * <p>谁读写它：{@code ToolPublishService} 负责创建、测试、发布、停用；
 * 运行时不直接读它，而是读仓储关联出的工具目录。</p>
 *
 * <p>它不负责什么：不保存远程工具清单（那在版本记录里）、不保存调用记录、不做权限判断。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpDefinitionEntity {

    /** 数据库自增主键；仅库内使用，对外一律用业务编号，避免主键规律被外部推断。 */
    private Long id;
    /** 所属租户；所有查询都必须带上它做隔离，否则会看到别的公司的工具配置。 */
    private String tenantId;
    /** 所有者用户编号；私有工具只有他本人或租户管理员能改，是权限判断的核心依据。 */
    private String ownerUserId;
    /** 可见范围（private 或 tenant_public）；决定同租户其他成员能不能在对话里用到它。 */
    private String visibility;
    /** MCP 稳定业务编号；对外唯一标识，也是运行时审计里的工具编号。 */
    private String mcpId;
  /** 展示名称；界面和模型函数描述都用它。 */
    private String mcpName;
    /** 草稿态的传输类型（sse/stdio）；改它只影响下一个新建版本，不影响已发布版本的运行行为。 */
    private String transportType;
    /** 草稿态的 SSE 地址；同样只对新版本生效，日志里不应打印它可能携带的鉴权查询串。 */
    private String endpoint;
    /** 草稿态的 stdio 启动命令；改动前后都受「只有管理员能配 stdio」这条规则约束。 */
    private String command;
    /** 草稿态的 stdio 参数 JSON；入库前已校验过是合法的字符串数组。 */
    private String args;
    /**
   * 草稿态的 stdio 环境变量 JSON；外部密钥常放在这里。
     * 属敏感数据，查询返回给管理界面时应谨慎，绝不能进入模型可见的任何文本。
     */
    private String env;
    /** 人类可读的用途说明；会进入模型函数描述，直接影响模型选不选这个工具。 */
    private String description;
    /** 最近编辑的版本号；创建新版本时会推进它，但它不代表线上正在用的版本。 */
    private String currentVersion;
    /** 已发布、对运行时可见的版本号；模型实际调用的就是这一版，是「线上跑什么」的唯一答案。 */
    private String publishedVersion;
    /** 当前激活版本的记录编号；发布时一起写入，便于直接定位到那条冻结的版本记录。 */
    private String activeVersionId;
    /** 最近一次连接测试的结论（untested/success/failed）；发布前会检查对应版本是否 success。 */
    private String testStatus;
    /** 最近一次测试的摘要文案，例如发现了几个远程工具或失败原因；供管理界面展示，帮用户判断该怎么改配置。 */
    private String testMessage;
    /** 最近一次测试时间；无论成功失败都会更新，用来判断这条配置的连通性结论有多旧。 */
    private LocalDateTime lastTestTime;
    /** 定义的生命周期状态（draft/active/disabled）；只有 active 的定义才会被查进运行目录。 */
    private String status;
    /** 预留扩展元数据 JSON；当前流程不写，留给以后加字段而不改表结构。 */
    private String metadata;
}
