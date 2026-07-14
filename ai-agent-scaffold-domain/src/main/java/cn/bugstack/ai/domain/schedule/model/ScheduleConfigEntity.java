package cn.bugstack.ai.domain.schedule.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 定时任务配置实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleConfigEntity {

    private String tenantId;
    private String ownerUserId;
    private String runAsUserId;
    private String runAsRoleCode;
    private String configId;
    private String agentId;
    private String agentName;
    private String taskType;
    private String taskPayload;
    private String cronExpr;
    private String timezone;
    private boolean enabled;
    private String status;
    private String misfirePolicy;
    private int maxRetries;
    private String configHash;
    private long configVersion;
    private LocalDateTime lastReconciledAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
