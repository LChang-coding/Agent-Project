package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工具调用日志实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallLogEntity {

    private Long id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String runId;
    private String workflowId;
    private String toolType;
    private String toolId;
    private String toolName;
    private String version;
    private String invocationId;
    private String functionCallId;
    private String idempotencyKey;
    private String traceId;
    private String inputJson;
    private String outputJson;
    private String status;
    private LocalDateTime startedAt;
    private String errorType;
    private String errorMessage;
    private Long costMs;
    private String metadata;
    private LocalDateTime createTime;
}
