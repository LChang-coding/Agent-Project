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

    private Long id;
    private String tenantId;
    private String ownerUserId;
    private String visibility;
    private String mcpId;
    private String mcpName;
    private String transportType;
    private String endpoint;
    private String command;
    private String args;
    private String env;
    private String description;
    private String currentVersion;
    private String publishedVersion;
    private String activeVersionId;
    private String testStatus;
    private String testMessage;
    private LocalDateTime lastTestTime;
    private String status;
    private String metadata;
}
