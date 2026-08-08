package cn.bugstack.ai.infrastructure.rag.outbox;

import cn.bugstack.ai.infrastructure.dao.IRagOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxCandidatePO;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxPO;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.AiLogFields;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;

/**
 * RAG Outbox 可靠发布器。
 * <p>Kafka 投递在领取事务外执行；只有明确 ACK 后才使用 fencing CAS 标记 published。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag.outbox", name = "enabled", havingValue = "true")
public class RagOutboxPublisher {

    /** 持久化错误摘要硬上限，避免异常链撑大数据库。 */
    private static final int MAX_ERROR_LENGTH = 1000;

    /** 扫描事件、写发布终态和失败重试状态的持久化入口。 */
    private final IRagOutboxDao outboxDao;
    /** 在独立事务中原子领取事件并回读新的围栏令牌。 */
    private final RagOutboxClaimService claimService;
    /** 将摄取唤醒事件发送到配置的 Kafka Topic。 */
    private final KafkaTemplate<String, String> kafkaTemplate;
    /** 提供批量、租约、ACK 超时和退避参数。 */
    private final RagProperties properties;
    /** 为扫描、租约和重试时间提供可测试的 UTC 时钟。 */
    private final Clock clock;
    /** 为指数退避生成可测试的随机抖动。 */
    private final DoubleSupplier random;
    /** 标识当前发布器实例，只有该持有者可以确认本次领取结果。 */
    private final String leaseOwner;

    /** 创建生产发布器。 */
    @Autowired
    public RagOutboxPublisher(IRagOutboxDao outboxDao, RagOutboxClaimService claimService,
                              KafkaTemplate<String, String> kafkaTemplate, RagProperties properties) {
        this(outboxDao, claimService, kafkaTemplate, properties, Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble(),
                "rag-outbox-" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** 创建可测试发布器；时钟、随机源和节点标识由调用方提供。 */
    public RagOutboxPublisher(IRagOutboxDao outboxDao, RagOutboxClaimService claimService,
                              KafkaTemplate<String, String> kafkaTemplate, RagProperties properties,
                              Clock clock, DoubleSupplier random, String leaseOwner) {
        this.outboxDao = outboxDao;
        this.claimService = claimService;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
        this.leaseOwner = leaseOwner;
    }

    /** 扫描并发布一批到期事件。 */
    @Scheduled(fixedDelayString = "${ai.rag.outbox.poll-delay-ms:1000}")
    /** 扫描最小候选并逐条领取发布；候选本身不授予发布权。 */
    public void publishDueBatch() {
        RagProperties.Outbox config = properties.getOutbox();
        if (config == null || !config.isEnabled()) return;
        LocalDateTime scanTime = now();
        for (RagOutboxCandidatePO candidate : outboxDao.queryDueCandidates(scanTime, config.getBatchSize())) {
            if (Thread.currentThread().isInterrupted()) return;
            publishCandidate(candidate, config);
        }
    }

    /** Broker 确认后才写 published；围栏失效则拒绝覆盖新持有者状态。 */
    private void publishCandidate(RagOutboxCandidatePO candidate, RagProperties.Outbox config) {
        LocalDateTime claimTime = now();
        long claimStarted = System.nanoTime();
        AiLog.info(AiLog.rag().ingestStageStarted(candidate.getTenantId(), null,
                null, null, "outbox_claim", "开始领取待发布的摄取Outbox事件", 1)
                .field("eventId", candidate.getEventId()));
        Optional<RagOutboxPO> claimed = claimService.claim(candidate, leaseOwner, claimTime,
                claimTime.plusNanos(TimeUnit.MILLISECONDS.toNanos(config.getLeaseDurationMs())));
        if (claimed.isEmpty()) {
            outboxDao.markExhaustedDead(candidate.getTenantId(), candidate.getEventId(),
                    "OUTBOX_MAX_ATTEMPTS_EXHAUSTED");
            AiLog.warn(AiLog.rag().ingestFailed(candidate.getTenantId(), null,
                    null, null, "outbox_claim", "RAG_OUTBOX_MAX_ATTEMPTS_EXHAUSTED",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - claimStarted), null)
                    .field("eventId", candidate.getEventId()));
            return;
        }
        RagOutboxPO event = claimed.get();
        AiLog.info(AiLog.rag().ingestStageCompleted(event.getTenantId(), event.getTaskId(),
                        null, null, "outbox_claim", "摄取Outbox事件领取完成",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - claimStarted), 1, 1)
                .field("eventId", event.getEventId()).field(AiLogFields.TRACE_ID, event.getTraceId()));
        try {
            long publishStarted = System.nanoTime();
            AiLog.info(AiLog.rag().ingestStageStarted(event.getTenantId(), event.getTaskId(),
                            null, null, "kafka_publish", "开始发布摄取Kafka唤醒事件", 1)
                    .field("eventId", event.getEventId()).field(AiLogFields.TRACE_ID, event.getTraceId()));
            kafkaTemplate.send(event.getTopicName(), event.getPartitionKey(), event.getPayload())
                    .get(config.getAckTimeoutMs(), TimeUnit.MILLISECONDS);
            int changed = outboxDao.markPublished(event.getTenantId(), event.getEventId(), leaseOwner,
                    required(event.getFencingToken()), now());
            if (changed != 1) {
                log.warn("RAG Outbox ACK后栅栏已失效 eventId:{}", event.getEventId());
            } else {
                AiLog.info(AiLog.rag().ingestStageCompleted(event.getTenantId(), event.getTaskId(),
                        null, null, "kafka_publish", "Kafka已确认摄取唤醒事件",
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - publishStarted), 1, 1)
                        .field("eventId", event.getEventId())
                        .field(AiLogFields.TRACE_ID, event.getTraceId()));
            }
        } catch (InterruptedException e) {
            recordFailure(event, e, config);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            recordFailure(event, e, config);
        }
    }

    /** 按尝试次数选择 retry/dead，并写入抖动退避时间。 */
    private void recordFailure(RagOutboxPO event, Throwable error, RagProperties.Outbox config) {
        LocalDateTime failedAt = now();
        String safeError = sanitize(error);
        int attempts = event.getAttemptCount() == null ? 0 : event.getAttemptCount();
        int maxAttempts = event.getMaxAttempts() == null ? 1 : event.getMaxAttempts();
        int changed;
        if (attempts >= maxAttempts) {
            changed = outboxDao.markDead(event.getTenantId(), event.getEventId(), leaseOwner,
                    required(event.getFencingToken()), failedAt, safeError);
        } else {
            LocalDateTime retryAt = failedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(
                    retryDelayMillis(attempts, config)));
            changed = outboxDao.markRetrying(event.getTenantId(), event.getEventId(), leaseOwner,
                    required(event.getFencingToken()), failedAt, retryAt, safeError);
        }
        if (changed != 1) {
            log.warn("RAG Outbox失败状态栅栏已失效 eventId:{}", event.getEventId());
        } else {
            AiLog.warn(AiLog.rag().ingestFailed(event.getTenantId(), event.getTaskId(), null,
                    null, "outbox_publish", "RAG_OUTBOX_PUBLISH_FAILED", null, error)
                    .field("eventId", event.getEventId())
                    .field(AiLogFields.TRACE_ID, event.getTraceId()));
        }
    }

    /** 计算带上下限和随机抖动的指数退避，避免故障恢复时集中重试。 */
    private long retryDelayMillis(int attemptCount, RagProperties.Outbox config) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long multiplier = 1L << exponent;
        long capped = config.getRetryBaseDelayMs() > config.getRetryMaxDelayMs() / multiplier
                ? config.getRetryMaxDelayMs()
                : Math.min(config.getRetryMaxDelayMs(), config.getRetryBaseDelayMs() * multiplier);
        double jitter = config.getRetryJitterRatio();
        double factor = 1D - jitter + 2D * jitter * random.getAsDouble();
        return Math.max(1L, Math.min(config.getRetryMaxDelayMs(), Math.round(capped * factor)));
    }

    /** 只持久化异常类型并限制长度，避免泄露 Kafka 响应或撑大错误字段。 */
    private String sanitize(Throwable error) {
        Throwable cause = error instanceof ExecutionException && error.getCause() != null
                ? error.getCause() : error;
        String value = "KAFKA_PUBLISH_FAILED:" + cause.getClass().getSimpleName();
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    /** 将缺失围栏转换为永远无法匹配的值，禁止无围栏确认状态。 */
    private long required(Long value) {
        return value == null ? -1L : value;
    }

    /** 将统一时钟转换为数据库使用的 UTC 本地时间。 */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
