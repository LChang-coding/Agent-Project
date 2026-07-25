package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 版本实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpVersionEntity {

    /** 数据库主键。 */
    private Long id;
    /** 版本所属租户。 */
    private String tenantId;
    /** 定义所有者快照。 */
    private String ownerUserId;
    /** 关联 MCP 业务 ID。 */
    private String mcpId;
    /** 版本记录稳定业务 ID。 */
    private String versionId;
    /** 用户可见版本号。 */
    private String version;
    /** 冻结的传输类型。 */
    private String transportType;
    /** 冻结的 SSE 地址。 */
    private String endpoint;
    /** 冻结的 Stdio 命令。 */
    private String command;
    /** 冻结的 Stdio 参数 JSON。 */
    private String args;
    /** 冻结的 Stdio 环境变量 JSON。 */
    private String env;
    /** 初始化发现的工具 schema JSON。 */
    private String toolSchemaJson;
    /** 该版本测试状态。 */
    private String testStatus;
    /** 该版本测试摘要。 */
    private String testMessage;
    /** 版本生命周期状态。 */
    private String status;
    /** 扩展元数据 JSON。 */
    private String metadata;
}
