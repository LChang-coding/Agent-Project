package cn.bugstack.ai.api.dto.schedule;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务逻辑执行响应。
 */
@Data
@Builder
public class ScheduleExecutionResponseDTO {
    private String executionId;
    private String traceId;
    private LocalDateTime plannedTime;
    private int attemptNo;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String errorMessage;
}
