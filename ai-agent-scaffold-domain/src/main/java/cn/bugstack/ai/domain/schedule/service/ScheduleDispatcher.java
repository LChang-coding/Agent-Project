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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
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
    private final ThreadPoolExecutor dispatchExecutor;
    private final ReentrantLock batchLock = new ReentrantLock();
    private final Object submissionMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
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
        AtomicInteger workerSequence = new AtomicInteger();
        int concurrency = dispatchConcurrency();
        this.dispatchExecutor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), runnable -> {
            Thread thread = new Thread(runnable, "schedule-dispatch-worker-" + workerSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.instanceId = hostName() + ":" + UUID.randomUUID();
    }

    public int dispatchBatch(int maxTasks) {
        batchLock.lock();
        try {
            if (closed.get()) {
                return 0;
            }
            int processed = 0;
            int limit = Math.max(1, Math.min(maxTasks, 500));
            int concurrency = dispatchConcurrency();
            while (processed < limit && !closed.get()) {
                int waveLimit = Math.min(concurrency, limit - processed);
                List<ScheduleTaskEntity> claimed;
                List<Future<?>> futures;
                synchronized (submissionMonitor) {
                    if (closed.get()) {
                        break;
                    }
                    claimed = claimWave(waveLimit);
                    futures = submitWave(claimed);
                }
                if (claimed.isEmpty()) {
                    break;
                }
                awaitWave(futures);
                processed += claimed.size();
                if (claimed.size() < waveLimit) {
                    break;
                }
            }
            return processed;
        } finally {
            batchLock.unlock();
        }
    }

    private List<ScheduleTaskEntity> claimWave(int waveLimit) {
        List<ScheduleTaskEntity> claimed = new ArrayList<>(waveLimit);
        for (int i = 0; i < waveLimit; i++) {
            LocalDateTime now = LocalDateTime.now(clock);
            String leaseOwner = instanceId + ":" + UUID.randomUUID();
            ScheduleTaskEntity task = repository.claimDueTask(leaseOwner, now,
                    now.plusSeconds(leaseSeconds()));
            if (task == null) {
                break;
            }
            claimed.add(task);
        }
        return claimed;
    }

    private List<Future<?>> submitWave(List<ScheduleTaskEntity> claimed) {
        List<Future<?>> futures = new ArrayList<>(claimed.size());
        for (ScheduleTaskEntity task : claimed) {
            futures.add(dispatchExecutor.submit(() -> dispatch(task)));
        }
        return futures;
    }

    private void awaitWave(List<Future<?>> futures) {
        boolean interrupted = false;
        for (Future<?> future : futures) {
            boolean completed = false;
            while (!completed) {
                try {
                    future.get();
                    completed = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    log.warn("定时任务 worker 异常终止", e.getCause());
                    completed = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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

    private int dispatchConcurrency() {
        return Math.max(1, Math.min(properties.getDispatchConcurrency(), 16));
    }

    @PreDestroy
    public void shutdown() {
        synchronized (submissionMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            dispatchExecutor.shutdownNow();
            heartbeatExecutor.shutdownNow();
        }
        batchLock.lock();
        try {
            // 已中断可中断 worker，在此等待本批失败或成功结果完成栅栏提交。
        } finally {
            batchLock.unlock();
        }
    }
}
