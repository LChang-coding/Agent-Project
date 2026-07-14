package cn.bugstack.ai.domain.schedule.service;

import cn.bugstack.ai.domain.schedule.adapter.IScheduleRepository;
import cn.bugstack.ai.domain.schedule.model.ScheduleConfigEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleExecutionEntity;
import cn.bugstack.ai.domain.schedule.model.ScheduleTaskEntity;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 以数据库短租约抢占任务，在事务外执行，再用栅栏令牌原子提交结果。
 */
@Service
public class ScheduleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);

    private final IScheduleRepository repository;
    private final CronScheduleSupport cronSupport;
    private final SchedulerProperties properties;
    private final Map<String, ScheduleTaskHandler> handlers;
    private final ScheduledExecutorService heartbeatExecutor;
    private final String instanceId;
    private final Clock clock = Clock.systemUTC();

    public ScheduleDispatcher(IScheduleRepository repository, CronScheduleSupport cronSupport,
                              SchedulerProperties properties, List<ScheduleTaskHandler> handlers) {
        this.repository = repository;
        this.cronSupport = cronSupport;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                ScheduleTaskHandler::taskType, Function.identity()));
        this.heartbeatExecutor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "schedule-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.instanceId = hostName() + ":" + UUID.randomUUID();
    }

    public int dispatchBatch(int maxTasks) {
        int processed = 0;
        int limit = Math.max(1, Math.min(maxTasks, 500));
        while (processed < limit) {
            LocalDateTime now = LocalDateTime.now(clock);
            String leaseOwner = instanceId + ":" + UUID.randomUUID();
            ScheduleTaskEntity task = repository.claimDueTask(leaseOwner, now,
                    now.plusSeconds(leaseSeconds()));
            if (task == null) break;
            dispatch(task);
            processed++;
        }
        return processed;
    }

    private void dispatch(ScheduleTaskEntity task) {
        ScheduleConfigEntity config = repository.findConfig(task.getConfigId());
        LocalDateTime plannedTime = task.getNextFireTime();
        if (config == null || !config.isEnabled() || !"active".equals(config.getStatus())) {
            repository.releaseFailedOccurrence(task.getTaskId(), task.getLeaseOwner(), task.getFencingToken(),
                    plannedTime, plannedTime.plusYears(100));
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now(clock);
        if ("skip".equals(task.getMisfirePolicy()) && plannedTime.isBefore(startedAt.minusSeconds(1))) {
            LocalDateTime next = cronSupport.next(task.getCronExpr(), task.getTimezone(), startedAt);
            repository.completeTask(task.getTaskId(), task.getLeaseOwner(), task.getFencingToken(), plannedTime, next);
            return;
        }
        ScheduleExecutionEntity execution = repository.beginExecution(ScheduleExecutionEntity.builder()
                .tenantId(task.getTenantId()).userId(task.getUserId()).configId(task.getConfigId())
                .taskId(task.getTaskId()).executionId("sche_" + UUID.randomUUID().toString().replace("-", ""))
                .triggerKey(triggerKey(task, plannedTime)).traceId(UUID.randomUUID().toString().replace("-", ""))
                .plannedTime(plannedTime).attemptNo(1).fencingToken(task.getFencingToken())
                .leaseOwner(task.getLeaseOwner()).startTime(startedAt).status("running").build());
        if (execution == null) {
            log.warn("调度触发点正在被其他栅栏执行 taskId:{} plannedTime:{}", task.getTaskId(), plannedTime);
            return;
        }
        LocalDateTime next = nextAfter(task, plannedTime, startedAt);
        if ("success".equals(execution.getStatus()) || "dead".equals(execution.getStatus())) {
            repository.completeTask(task.getTaskId(), task.getLeaseOwner(), task.getFencingToken(), plannedTime, next);
            return;
        }
        ScheduledFuture<?> heartbeat = startHeartbeat(task);
        long startedNanos = System.nanoTime();
        try {
            ScheduleTaskHandler handler = handlers.get(config.getTaskType());
            if (handler == null) throw new IllegalStateException("不支持的定时任务类型：" + config.getTaskType());
            String result = executeWithIdentity(config, execution, handler);
            long duration = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            repository.finishSuccess(execution.getExecutionId(), task.getTaskId(), task.getLeaseOwner(),
                    task.getFencingToken(), LocalDateTime.now(clock), duration, result, plannedTime, next);
        } catch (Exception e) {
            long duration = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            int retryCount = task.getRetryCount() + 1;
            boolean terminal = retryCount > task.getMaxRetries();
            LocalDateTime retryAt = LocalDateTime.now(clock).plusSeconds(backoffSeconds(retryCount));
            try {
                repository.finishFailure(execution.getExecutionId(), task.getTaskId(), task.getLeaseOwner(),
                        task.getFencingToken(), LocalDateTime.now(clock), duration, readableMessage(e), terminal,
                        retryCount, retryAt, plannedTime, next);
            } catch (Exception commitFailure) {
                log.warn("调度失败结果未提交，等待租约恢复 taskId:{}", task.getTaskId(), commitFailure);
            }
            log.warn("定时任务执行失败 taskId:{} attempt:{} terminal:{}", task.getTaskId(), retryCount,
                    terminal, e);
        } finally {
            heartbeat.cancel(false);
            TenantContextHolder.clear();
        }
    }

    private String executeWithIdentity(ScheduleConfigEntity config, ScheduleExecutionEntity execution,
                                       ScheduleTaskHandler handler) throws Exception {
        TenantContextHolder.set(TenantContext.builder().tenantId(config.getTenantId())
                .userId(config.getRunAsUserId()).roleCode(config.getRunAsRoleCode()).build());
        try {
            return handler.execute(new ScheduleTaskContext(config, execution));
        } finally {
            TenantContextHolder.clear();
        }
    }

    private ScheduledFuture<?> startHeartbeat(ScheduleTaskEntity task) {
        int interval = Math.max(5, Math.min(properties.getHeartbeatSeconds(),
                Math.max(5, properties.getLeaseSeconds() / 2)));
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                boolean renewed = repository.renewLease(task.getTaskId(), task.getLeaseOwner(),
                        task.getFencingToken(), LocalDateTime.now(clock).plusSeconds(leaseSeconds()));
                if (!renewed) log.warn("定时任务续租失败 taskId:{} fencing:{}", task.getTaskId(), task.getFencingToken());
            } catch (Exception e) {
                log.warn("定时任务续租异常 taskId:{}", task.getTaskId(), e);
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private LocalDateTime nextAfter(ScheduleTaskEntity task, LocalDateTime planned, LocalDateTime now) {
        String policy = task.getMisfirePolicy() == null ? "fire_once_now" : task.getMisfirePolicy();
        LocalDateTime cursor = "catch_up".equals(policy) ? planned : now;
        return cronSupport.next(task.getCronExpr(), task.getTimezone(), cursor);
    }

    private String triggerKey(ScheduleTaskEntity task, LocalDateTime planned) {
        return task.getBusinessKey() + ":" + task.getConfigVersion() + ":" + planned;
    }

    private long backoffSeconds(int retryCount) {
        long multiplier = 1L << Math.min(Math.max(0, retryCount - 1), 10);
        return Math.min(3600L, Math.max(1, properties.getRetryBaseSeconds()) * multiplier);
    }

    private String readableMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.substring(0, Math.min(1000, message.length()));
    }

    private String hostName() {
        String host = System.getenv("HOSTNAME");
        return host == null || host.isBlank() ? "unknown-host" : host;
    }

    private int leaseSeconds() {
        return Math.max(15, properties.getLeaseSeconds());
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }
}
