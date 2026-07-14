package cn.bugstack.ai.api.dto.schedule;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务配置响应。
 */
@Data
@Builder
public class ScheduleResponseDTO {
    private String configId;
    private String agentId;
    private String agentName;
    private String message;
    private String cronExpr;
    private String timezone;
    private boolean enabled;
    private String status;
    private String misfirePolicy;
    private int maxRetries;
    private long configVersion;
    private LocalDateTime lastReconciledAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
