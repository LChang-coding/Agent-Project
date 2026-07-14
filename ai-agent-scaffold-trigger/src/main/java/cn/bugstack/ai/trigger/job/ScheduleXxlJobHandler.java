package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.schedule.service.ScheduleDispatcher;
import cn.bugstack.ai.domain.schedule.service.ScheduleReconciler;
import cn.bugstack.ai.domain.schedule.service.SchedulerProperties;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * XXL-JOB 仅负责分布式唤醒，业务状态仍由项目数据库控制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleXxlJobHandler {

    private final ScheduleReconciler reconciler;
    private final ScheduleDispatcher dispatcher;
    private final SchedulerProperties properties;

    @XxlJob("scheduleReconcileJobHandler")
    public void reconcile() {
        int count = reconciler.reconcileBatch(properties.getReconcileBatchSize());
        log.info("XXL-JOB 调度配置对账完成 count:{}", count);
    }

    @XxlJob("scheduleDispatchJobHandler")
    public void dispatch() {
        int count = dispatcher.dispatchBatch(properties.getDispatchBatchSize());
        log.info("XXL-JOB 到期任务派发完成 count:{}", count);
    }
}
