package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用日志响应。
 */
@Data
public class ToolCallLogResponseDTO {

    /**
     * 工具类型。
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
     * 工具版本。
     */
    private String version;

    /**
     * 调用ID。
     */
    private String invocationId;

    /**
     * 链路ID。
     */
    private String traceId;

    /**
     * 调用状态。
     */
    private String status;

    /**
     * 错误类型。
     */
    private String errorType;

    /**
     * 错误信息。
     */
    private String errorMessage;

    /**
     * 耗时毫秒。
     */
    private Long costMs;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;
}
