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
}
