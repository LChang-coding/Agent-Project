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
    /** 数据库到期扫描与有界本地执行器。 */
    private final RagKnowledgeBaseDeletionRepository repository;
    private final RagKnowledgeBaseDeleteCoordinator coordinator;
    private final RagProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final String instanceId = "kb-delete:" + UUID.randomUUID();
    private final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

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
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
    }
}
