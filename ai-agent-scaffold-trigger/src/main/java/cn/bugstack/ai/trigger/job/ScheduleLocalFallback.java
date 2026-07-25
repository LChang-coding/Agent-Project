package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.schedule.service.ScheduleDispatcher;
import cn.bugstack.ai.domain.schedule.service.ScheduleReconciler;
import cn.bugstack.ai.domain.schedule.service.SchedulerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地定时任务降级入口。
 * <p>仅在 XXL-JOB 不可用且显式开启配置时唤醒领域调度器；任务认领和状态推进仍由数据库保证。</p>
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.scheduler", name = "local-fallback-enabled", havingValue = "true")
public class ScheduleLocalFallback {

    private final ScheduleReconciler reconciler;
    private final ScheduleDispatcher dispatcher;
    private final SchedulerProperties properties;

    /**
     * 周期性同步 Cron 配置。
     * <p>批量上限来自统一调度配置，避免本地兜底一次扫描压垮数据库。</p>
     */
    @Scheduled(fixedDelayString = "${ai.scheduler.local-reconcile-delay-ms:300000}")
    public void reconcile() {
        reconciler.reconcileBatch(properties.getReconcileBatchSize());
    }

    /**
     * 周期性派发已到执行时间的任务。
     * <p>本方法只负责唤醒，抢占、幂等和下一次执行时间由领域服务处理。</p>
     */
    @Scheduled(fixedDelayString = "${ai.scheduler.local-dispatch-delay-ms:5000}")
    public void dispatch() {
        dispatcher.dispatchBatch(properties.getDispatchBatchSize());
    }
}
