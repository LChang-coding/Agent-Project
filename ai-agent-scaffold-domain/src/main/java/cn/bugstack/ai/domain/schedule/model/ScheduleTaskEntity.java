package cn.bugstack.ai.domain.schedule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一份配置对应的调度运行态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleTaskEntity {

    private String tenantId;
    private String userId;
    private String configId;
    private String taskId;
    private String businessKey;
    private String configHash;
    private long configVersion;
    private String cronExpr;
    private String timezone;
    private String misfirePolicy;
    private int maxRetries;
    private LocalDateTime plannedTime;
    private LocalDateTime nextFireTime;
    private LocalDateTime lastPlannedTime;
    private LocalDateTime retryAt;
    private String status;
    private int retryCount;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private long fencingToken;
    private long rowVersion;
}
