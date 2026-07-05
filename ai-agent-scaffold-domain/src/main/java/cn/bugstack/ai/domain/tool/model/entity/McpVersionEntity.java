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

    private Long id;
    private String tenantId;
    private String ownerUserId;
    private String mcpId;
    private String versionId;
    private String version;
    private String transportType;
    private String endpoint;
    private String command;
    private String args;
    private String env;
    private String toolSchemaJson;
    private String testStatus;
    private String testMessage;
    private String status;
    private String metadata;
}
