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

    /** 单次配置收敛扫描上限。 */
    private int reconcileBatchSize = 200;
    /** 单次到期任务扫描上限。 */
    private int dispatchBatchSize = 50;
    /** 单实例并行执行任务数。 */
    private int dispatchConcurrency = 2;
    /** 抢占租约有效秒数。 */
    private int leaseSeconds = 120;
    /** 执行中续租间隔秒数。 */
    private int heartbeatSeconds = 30;
    /** 指数退避的基础秒数。 */
    private int retryBaseSeconds = 30;
    /** XXL-JOB 不可用时是否启用本地轮询兜底。 */
    private boolean localFallbackEnabled = false;
}
