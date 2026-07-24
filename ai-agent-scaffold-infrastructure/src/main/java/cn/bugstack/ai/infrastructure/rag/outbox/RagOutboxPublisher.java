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

    private static final int MAX_ERROR_LENGTH = 1000;

    private final IRagOutboxDao outboxDao;
    private final RagOutboxClaimService claimService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RagProperties properties;
    private final Clock clock;
    private final DoubleSupplier random;
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
    public void publishDueBatch() {
        RagProperties.Outbox config = properties.getOutbox();
        if (config == null || !config.isEnabled()) return;
        LocalDateTime scanTime = now();
        for (RagOutboxCandidatePO candidate : outboxDao.queryDueCandidates(scanTime, config.getBatchSize())) {
            if (Thread.currentThread().isInterrupted()) return;
            publishCandidate(candidate, config);
        }
    }

    private void publishCandidate(RagOutboxCandidatePO candidate, RagProperties.Outbox config) {
        LocalDateTime claimTime = now();
        Optional<RagOutboxPO> claimed = claimService.claim(candidate, leaseOwner, claimTime,
                claimTime.plusNanos(TimeUnit.MILLISECONDS.toNanos(config.getLeaseDurationMs())));
        if (claimed.isEmpty()) {
            outboxDao.markExhaustedDead(candidate.getTenantId(), candidate.getEventId(),
                    "OUTBOX_MAX_ATTEMPTS_EXHAUSTED");
            return;
        }
        RagOutboxPO event = claimed.get();
        try {
            kafkaTemplate.send(event.getTopicName(), event.getPartitionKey(), event.getPayload())
                    .get(config.getAckTimeoutMs(), TimeUnit.MILLISECONDS);
            int changed = outboxDao.markPublished(event.getTenantId(), event.getEventId(), leaseOwner,
                    required(event.getFencingToken()), now());
            if (changed != 1) {
                log.warn("RAG Outbox ACK后栅栏已失效 eventId:{}", event.getEventId());
            } else {
                AiLog.info(AiLog.rag().ingestStageCompleted(event.getTenantId(), event.getTaskId(),
                        null, null, "outbox_published", null, null)
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

    private String sanitize(Throwable error) {
        Throwable cause = error instanceof ExecutionException && error.getCause() != null
                ? error.getCause() : error;
        String value = "KAFKA_PUBLISH_FAILED:" + cause.getClass().getSimpleName();
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private long required(Long value) {
        return value == null ? -1L : value;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
