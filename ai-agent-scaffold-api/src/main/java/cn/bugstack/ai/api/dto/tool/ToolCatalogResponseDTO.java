package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

/**
 * 工具目录响应。
 */
@Data
public class ToolCatalogResponseDTO {

    /**
     * 工具类型：skill/mcp。
     */
    private String toolType;

    /**
     * 工具ID。
     */
    private String toolId;

    /**
     * 工具名称。
     */
    private String toolName;

    /**
     * 工具编码。
     */
    private String toolCode;

    /**
     * 工具描述。
     */
    private String description;

    /**
     * 当前版本。
     */
    private String version;

    /**
     * 可见范围。
     */
    private String visibility;
}
