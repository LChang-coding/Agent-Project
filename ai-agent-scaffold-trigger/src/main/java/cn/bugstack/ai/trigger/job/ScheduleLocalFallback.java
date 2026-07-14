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
 * 仅供显式降级使用的本地唤醒器，生产默认关闭。
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.scheduler", name = "local-fallback-enabled", havingValue = "true")
public class ScheduleLocalFallback {

    private final ScheduleReconciler reconciler;
    private final ScheduleDispatcher dispatcher;
    private final SchedulerProperties properties;

    @Scheduled(fixedDelayString = "${ai.scheduler.local-reconcile-delay-ms:300000}")
    public void reconcile() {
        reconciler.reconcileBatch(properties.getReconcileBatchSize());
    }

    @Scheduled(fixedDelayString = "${ai.scheduler.local-dispatch-delay-ms:5000}")
    public void dispatch() {
        dispatcher.dispatchBatch(properties.getDispatchBatchSize());
    }
}
