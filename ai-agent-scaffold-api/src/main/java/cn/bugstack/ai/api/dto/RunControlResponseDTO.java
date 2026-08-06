package cn.bugstack.ai.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 运行控制响应。
 */
@Data
@Builder
public class RunControlResponseDTO {

    private String runId;
    private String sessionId;
    private String status;
    private Long contextRevision;
    private String successorRunId;
    private String ragInvocationMode;
    /** 被控制 Run 的根链路号；取消、引导等后续操作不得换号。 */
    private String traceId;
    /** 当前取消或引导 HTTP 请求的链路号，用于单独审计本次操作。 */
    private String operationTraceId;
}
