package cn.bugstack.ai.domain.schedule.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务调度器运行参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.scheduler")
public class SchedulerProperties {

    private int reconcileBatchSize = 200;
    private int dispatchBatchSize = 50;
    private int dispatchConcurrency = 2;
    private int leaseSeconds = 120;
    private int heartbeatSeconds = 30;
    private int retryBaseSeconds = 30;
    private boolean localFallbackEnabled = false;
}
