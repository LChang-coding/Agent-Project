package cn.bugstack.ai.infrastructure.rag.worker;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobCandidate;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.AiLogFields;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Kafka 唤醒 + MySQL 恢复扫描共用的单线程摄取调度器。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag.worker", name = "enabled", havingValue = "true")
public class RagIngestDispatcher {

    /** 到期扫描、执行器和配置；inFlight 只防本进程重复提交。 */
    private final IRagRepository repository;
    private final RagIngestWorker worker;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();
    private final String instanceId = instanceId();
    private final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RagIngestDispatcher(IRagRepository repository, RagIngestWorker worker,
                               RagProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.worker = worker;
        this.properties = properties;
        this.objectMapper = objectMapper;
        int capacity = Math.max(2, properties.getWorker().getScanBatchSize() * 2);
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, "rag-ingest-worker-1");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Kafka 只传标识符并作为唤醒；任务内容始终回查 MySQL。 */
    @KafkaListener(topics = "${ai.rag.kafka.topic:rag.ingest.request.v1}",
            groupId = "${ai.rag.kafka.group-id:ai-agent-rag-ingest}",
            autoStartup = "${ai.rag.kafka.listener-enabled:false}")
    /** 校验 Kafka 负载后只提交 tenantId + jobId，任务状态以数据库为准。 */
    public void consume(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root.path("schemaVersion").asInt(-1) != 1) {
                throw new AppException("RAG_INGEST_EVENT_VERSION_UNSUPPORTED", "RAG 摄取事件版本不支持");
            }
            String tenantId = required(root, "tenantId");
            String taskId = required(root, "taskId");
            AiLog.info(AiLog.rag().ingestStageCompleted(tenantId, taskId, null, null,
                            "kafka_wakeup_received", "Kafka摄取唤醒事件校验完成", 0L, 1, 1)
                    .field(AiLogFields.TRACE_ID, root.path("traceId").asText(null)));
            submit(tenantId, taskId);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("RAG_INGEST_EVENT_INVALID", "RAG 摄取事件格式非法");
        }
    }

    /** 补偿 Kafka 重复/丢失、应用重启、到期重试和过期租约。 */
    @Scheduled(fixedDelayString = "${ai.rag.worker.poll-delay-ms:2000}")
    /** Kafka 丢失或停用时的数据库兜底扫描。 */
    public void scanDueTasks() {
        if (closed.get()) return;
        for (RagIngestJobCandidate candidate : repository.listDueIngestJobCandidates(
                clock.instant(), properties.getWorker().getScanBatchSize())) {
            submit(candidate.tenantId(), candidate.jobId());
        }
    }

    /** 有界线程池拒绝过载；同任务在本进程最多一个执行。 */
    public boolean submit(String tenantId, String jobId) {
        if (closed.get()) return false;
        String key = tenantId + ":" + jobId;
        if (!inFlight.add(key)) {
            AiLog.info(AiLog.rag().ingestStageCompleted(tenantId, jobId, null, null,
                    "worker_deduplicate", "任务已在本实例执行，忽略重复唤醒", 0L, 1, 0));
            return false;
        }
        try {
            executor.execute(() -> {
                long startedAt = System.nanoTime();
                AiLog.info(AiLog.rag().ingestStageStarted(tenantId, jobId, null, null,
                        "worker_dispatch", "单Worker开始执行摄取任务", 1));
                try {
                    worker.execute(tenantId, jobId, instanceId + ":" + UUID.randomUUID());
                    AiLog.info(AiLog.rag().ingestStageCompleted(tenantId, jobId, null, null,
                            "worker_dispatch", "单Worker本次任务执行返回",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), 1, 1));
                } catch (Exception e) {
                    log.warn("RAG Worker未捕获异常 tenantId:{} jobId:{} type:{}",
                            tenantId, jobId, e.getClass().getSimpleName());
                } finally {
                    inFlight.remove(key);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.remove(key);
            AiLog.warn(AiLog.rag().ingestFailed(tenantId, jobId, null, null,
                    "worker_queue", "RAG_WORKER_QUEUE_FULL", null, e));
            log.warn("RAG Worker单线程队列已满，等待下次数据库扫描 tenantId:{} jobId:{}", tenantId, jobId);
            return false;
        }
    }

    private String required(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new AppException("RAG_INGEST_EVENT_INVALID", "RAG 摄取事件缺少必填标识符");
        }
        return value;
    }

    private String instanceId() {
        String host = System.getenv("HOSTNAME");
        return (host == null || host.isBlank() ? "local" : host) + ":" + UUID.randomUUID();
    }

    @PreDestroy
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
    }
}
