package cn.bugstack.ai.domain.schedule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 同一计划触发点的逻辑执行记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleExecutionEntity {

    private String tenantId;
    private String userId;
    private String configId;
    private String taskId;
    private String executionId;
    private String triggerKey;
    private String traceId;
    private LocalDateTime plannedTime;
    private int attemptNo;
    private long fencingToken;
    private String leaseOwner;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String status;
    private String errorMessage;
    private String resultJson;
}
