package cn.bugstack.ai.trigger.job;

import cn.bugstack.ai.domain.schedule.service.ScheduleDispatcher;
import cn.bugstack.ai.domain.schedule.service.ScheduleReconciler;
import cn.bugstack.ai.domain.schedule.service.SchedulerProperties;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * XXL-JOB 分布式调度入口。
 * <p>Admin 只负责按时触发 Handler，项目数据库才是配置、任务状态和幂等执行的事实来源。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleXxlJobHandler {

    private final ScheduleReconciler reconciler;
    private final ScheduleDispatcher dispatcher;
    private final SchedulerProperties properties;

    /**
     * 对账 Cron 配置并生成或更新未来任务。
     * <p>返回数量仅用于观测本轮工作量，不参与 XXL-JOB 的任务状态判断。</p>
     */
    @XxlJob("scheduleReconcileJobHandler")
    public void reconcile() {
        int count = reconciler.reconcileBatch(properties.getReconcileBatchSize());
        log.info("XXL-JOB 调度配置对账完成 count:{}", count);
    }

    /**
     * 扫描并派发当前已到期任务。
     * <p>领域调度器负责竞争认领，多个执行器同时触发时不得重复执行同一任务。</p>
     */
    @XxlJob("scheduleDispatchJobHandler")
    public void dispatch() {
        int count = dispatcher.dispatchBatch(properties.getDispatchBatchSize());
        log.info("XXL-JOB 到期任务派发完成 count:{}", count);
    }
}
