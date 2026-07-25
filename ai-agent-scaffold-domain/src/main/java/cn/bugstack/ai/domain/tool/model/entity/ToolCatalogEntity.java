package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前工具目录实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCatalogEntity {

    /** skill 或 mcp。 */
    private String toolType;
    /** 工具稳定业务 ID。 */
    private String toolId;
    /** 展示名称。 */
    private String toolName;
    /** Skill 编码；MCP 可为空。 */
    private String toolCode;
    /** 用途说明。 */
    private String description;
    /** 当前发布版本。 */
    private String version;
    /** private 或 tenant_public。 */
    private String visibility;
    /** private 工具所有者。 */
    private String ownerUserId;
    /** Skill 包来源类型。 */
    private String sourceType;
    /** Skill 包对象存储桶。 */
    private String bucket;
    /** Skill 包对象键。 */
    private String objectKey;
    /** MCP 的 sse/stdio 类型。 */
    private String transportType;
    /** MCP SSE 地址。 */
    private String endpoint;
    /** MCP Stdio 命令。 */
    private String command;
    /** MCP Stdio 参数 JSON。 */
    private String args;
    /** MCP Stdio 环境变量 JSON。 */
    private String env;
    /** 已发布版本的工具 schema JSON。 */
    private String schemaJson;
}
