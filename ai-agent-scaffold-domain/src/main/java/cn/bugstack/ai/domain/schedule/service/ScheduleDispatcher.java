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

    /** 记录任务领取、执行、重试和租约失效结果。 */
    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);

    /** 领取到期任务、续租并以条件更新提交执行结果。 */
    private final IScheduleRepository repository;
    /** 根据 Cron 和时区计算下一次计划执行时间。 */
    private final CronScheduleSupport cronSupport;
    /** 提供并发数、租约时长、批量大小和重试间隔。 */
    private final SchedulerProperties properties;
    /** 按任务类型选择实际业务处理器的不可变映射。 */
    private final Map<String, ScheduleTaskHandler> handlers;
    /** 在任务执行期间定时延长数据库租约。 */
    private final ScheduledExecutorService heartbeatExecutor;
    /** 有界并发执行已成功领取的调度任务。 */
    private final ThreadPoolExecutor dispatchExecutor;
    /** 保证同一实例同一时间只生产一个扫描批次。 */
    private final ReentrantLock batchLock = new ReentrantLock();
    /** 协调批次提交与关闭，避免关闭过程中继续提交任务。 */
    private final Object submissionMonitor = new Object();
    /** 标记调度器已停止接收和执行新任务。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 写入任务租约的实例标识，用于确认只有持有者可以续租和提交。 */
    private final String instanceId;
    /** 统一计划时间和租约判断使用的 UTC 时钟。 */
    private final Clock clock = Clock.systemUTC();

    /** 建立有界执行池、单线程续租器和任务类型到处理器的不可变映射。 */
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

    /** 串行组织一个扫描批次，分波并行执行，返回实际抢占任务数。 */
    public int dispatchBatch(int maxTasks) {
        // 单实例只允许一个批次生产任务，避免多个触发器把本地有界队列打满。
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
                    // 与 shutdown 共用监视器，封闭“检查未关闭后仍提交新任务”的竞态。
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

    /** 逐个原子抢占到期任务，每个任务使用独立租约所有者。 */
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

    /** 将已持有租约的任务提交到固定大小的有界工作池。 */
    private List<Future<?>> submitWave(List<ScheduleTaskEntity> claimed) {
        List<Future<?>> futures = new ArrayList<>(claimed.size());
        for (ScheduleTaskEntity task : claimed) {
            futures.add(dispatchExecutor.submit(() -> dispatch(task)));
        }
        return futures;
    }

    /** 等待本波任务完成，同时延迟恢复中断标记以免遗留无人提交的租约。 */
    private void awaitWave(List<Future<?>> futures) {
        boolean interrupted = false;
        for (Future<?> future : futures) {
            boolean completed = false;
            while (!completed) {
                try {
                    future.get();
                    completed = true;
                } catch (InterruptedException e) {
                    // 先等待所有已提交任务收尾，再把中断语义交还上层。
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

    /** 校验运行态、登记幂等执行、维持租约并用栅栏令牌提交最终结果。 */
    private void dispatch(ScheduleTaskEntity task) {
        ScheduleConfigEntity config = repository.findConfig(task.getConfigId());
        LocalDateTime plannedTime = task.getNextFireTime();
        if (config == null || !config.isEnabled() || !"active".equals(config.getStatus())) {
            // 抢占后配置可能已删除或停用，直接封存该触发点，不能再调用 Agent。
            repository.releaseFailedOccurrence(task.getTaskId(), task.getLeaseOwner(), task.getFencingToken(),
                    plannedTime, plannedTime.plusYears(100));
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now(clock);
        if ("skip".equals(task.getMisfirePolicy()) && plannedTime.isBefore(startedAt.minusSeconds(1))) {
            // skip 策略只推进游标，不补跑已经错过的业务动作。
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
            // 相同触发键已由另一实例登记，当前 Worker 不得重复产生副作用。
            log.warn("调度触发点正在被其他栅栏执行 taskId:{} plannedTime:{}", task.getTaskId(), plannedTime);
            return;
        }
        LocalDateTime next = nextAfter(task, plannedTime, startedAt);
        if ("success".equals(execution.getStatus()) || "dead".equals(execution.getStatus())) {
            // 幂等命中终态记录时只推进任务游标，不再执行处理器。
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
                // 执行记录与任务重试游标必须由仓储在同一事务内提交。
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

    /** 在当前工作线程恢复配置固化的租户身份，并保证执行后清理。 */
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

    /** 周期续租长任务；续租失败只记录，由最终栅栏提交决定结果是否有效。 */
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

    /** 根据错过策略选择从原计划点追赶，或从实际执行时刻继续。 */
    private LocalDateTime nextAfter(ScheduleTaskEntity task, LocalDateTime planned, LocalDateTime now) {
        String policy = task.getMisfirePolicy() == null ? "fire_once_now" : task.getMisfirePolicy();
        LocalDateTime cursor = "catch_up".equals(policy) ? planned : now;
        return cronSupport.next(task.getCronExpr(), task.getTimezone(), cursor);
    }

    /** 构造一个配置版本下单个计划时间的全局幂等键。 */
    private String triggerKey(ScheduleTaskEntity task, LocalDateTime planned) {
        return task.getBusinessKey() + ":" + task.getConfigVersion() + ":" + planned;
    }

    /** 计算封顶一小时的指数退避时间。 */
    private long backoffSeconds(int retryCount) {
        long multiplier = 1L << Math.min(Math.max(0, retryCount - 1), 10);
        return Math.min(3600L, Math.max(1, properties.getRetryBaseSeconds()) * multiplier);
    }

    /** 将异常转换为最长一千字符的持久化消息。 */
    private String readableMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.substring(0, Math.min(1000, message.length()));
    }

    /** 获取实例可读主机名；容器未注入时使用稳定占位值。 */
    private String hostName() {
        String host = System.getenv("HOSTNAME");
        return host == null || host.isBlank() ? "unknown-host" : host;
    }

    /** 强制租约不少于十五秒，避免高频抢占抖动。 */
    private int leaseSeconds() {
        return Math.max(15, properties.getLeaseSeconds());
    }

    /** 将单实例并发限制在一至十六之间。 */
    private int dispatchConcurrency() {
        return Math.max(1, Math.min(properties.getDispatchConcurrency(), 16));
    }

    /** 阻止新提交、中断后台线程，并等待当前批次退出关键区。 */
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
