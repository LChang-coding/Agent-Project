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

    private String toolType;
    private String toolId;
    private String toolName;
    private String toolCode;
    private String description;
    private String version;
    private String visibility;
    private String ownerUserId;
    private String sourceType;
    private String bucket;
    private String objectKey;
    private String transportType;
    private String endpoint;
    private String command;
    private String args;
    private String env;
    private String schemaJson;
}
