package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MCP 定义实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpDefinitionEntity {

    /** 数据库主键，不作为外部标识。 */
    private Long id;
    /** 定义所属租户。 */
    private String tenantId;
    /** private 工具的所有者。 */
    private String ownerUserId;
    /** private 或 tenant_public。 */
    private String visibility;
    /** MCP 稳定业务 ID。 */
    private String mcpId;
    /** 展示名称。 */
    private String mcpName;
    /** 当前草稿连接的 sse/stdio 类型。 */
    private String transportType;
    /** 当前草稿 SSE 地址。 */
    private String endpoint;
    /** 当前草稿 Stdio 命令。 */
    private String command;
    /** 当前草稿 Stdio 参数 JSON。 */
    private String args;
    /** 当前草稿 Stdio 环境变量 JSON。 */
    private String env;
    /** 人类可读用途说明。 */
    private String description;
    /** 最近编辑的版本号。 */
    private String currentVersion;
    /** 已对运行时可见的版本号。 */
    private String publishedVersion;
    /** 当前激活版本记录 ID。 */
    private String activeVersionId;
    /** 最近连接测试状态。 */
    private String testStatus;
    /** 最近连接测试摘要。 */
    private String testMessage;
    /** 最近测试时间。 */
    private LocalDateTime lastTestTime;
    /** 定义生命周期状态。 */
    private String status;
    /** 扩展元数据 JSON。 */
    private String metadata;
}
