package cn.bugstack.ai.infrastructure.rag.worker;

import cn.bugstack.ai.domain.rag.adapter.repository.RagKnowledgeBaseDeletionRepository;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 知识库删除使用独立单线程，避免等待子DELETE时阻塞摄取Worker。 */
@Component
@ConditionalOnProperty(prefix = "ai.rag.worker", name = "enabled", havingValue = "true")
public class RagKnowledgeBaseDeleteDispatcher {
    /** 扫描到期候选；数据库任务账本仍是执行权事实源。 */
    private final RagKnowledgeBaseDeletionRepository repository;
    /** 领取任务并按检查点协调各个文档子删除任务。 */
    private final RagKnowledgeBaseDeleteCoordinator coordinator;
    /** 提供扫描批量、轮询间隔和 Worker 参数。 */
    private final RagProperties properties;
    /** 为到期扫描提供统一 UTC 时间。 */
    private final Clock clock = Clock.systemUTC();
    /** 标识当前知识库删除调度器实例。 */
    private final String instanceId = "kb-delete:" + UUID.randomUUID();
    /** 只防止同一删除任务在本进程重复排队，不代替数据库租约。 */
    private final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 独立单线程有界执行器，避免等待子任务时占用摄取 Worker。 */
    private final ThreadPoolExecutor executor;
    /** 应用关闭后阻止扫描和新删除任务继续进入执行器。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 创建独立单 Worker 执行器，队列容量随扫描批量调整。 */
    public RagKnowledgeBaseDeleteDispatcher(RagKnowledgeBaseDeletionRepository repository,
                                             RagKnowledgeBaseDeleteCoordinator coordinator,
                                             RagProperties properties) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.properties = properties;
        int capacity = Math.max(2, properties.getWorker().getScanBatchSize() * 2);
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
            Thread thread = new Thread(runnable, "rag-kb-delete-worker-1");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString = "${ai.rag.worker.poll-delay-ms:2000}")
    /** 扫描只提交候选；每个候选仍由协调器原子领取。 */
    public void scanDueTasks() {
        if (closed.get()) return;
        repository.listDueCandidates(clock.instant(), properties.getWorker().getScanBatchSize())
                .forEach(candidate -> submit(candidate.tenantId(), candidate.taskId()));
    }

    /** 将候选提交到有界队列；本进程重复或队列已满时等待下次扫描。 */
    boolean submit(String tenantId, String taskId) {
        String key = tenantId + ":" + taskId;
        if (closed.get() || !inFlight.add(key)) return false;
        try {
            executor.execute(() -> {
                try {
                    coordinator.execute(tenantId, taskId, instanceId + ":" + UUID.randomUUID());
                } finally {
                    inFlight.remove(key);
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            inFlight.remove(key);
            return false;
        }
    }

    @PreDestroy
    /** 标记调度器关闭并中断仍在执行或排队的本地任务。 */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
    }
}
