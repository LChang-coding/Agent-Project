package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 定义响应。
 */
@Data
public class McpResponseDTO {

    /**
     * MCP ID。
     */
    private String mcpId;

    /**
     * MCP 名称。
     */
    private String mcpName;

    /**
     * MCP 描述。
     */
    private String description;

    /**
     * 可见范围。
     */
    private String visibility;

    /**
     * 传输类型。
     */
    private String transportType;

    /**
     * 远程地址。
     */
    private String endpoint;

    /**
     * 当前版本。
     */
    private String currentVersion;

    /**
     * 已发布版本。
     */
    private String publishedVersion;

    /**
     * 测试状态。
     */
    private String testStatus;

    /**
     * 测试信息。
     */
    private String testMessage;

    /**
     * 最后测试时间。
     */
    private LocalDateTime lastTestTime;

    /**
     * 发布状态。
     */
    private String status;
}
