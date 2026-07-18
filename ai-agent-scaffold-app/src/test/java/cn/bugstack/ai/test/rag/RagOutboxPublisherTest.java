package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.infrastructure.dao.IRagOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxCandidatePO;
import cn.bugstack.ai.infrastructure.dao.po.RagOutboxPO;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.infrastructure.rag.outbox.RagOutboxClaimService;
import cn.bugstack.ai.infrastructure.rag.outbox.RagOutboxPublisher;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RAG Outbox 可靠发布器测试。 */
public class RagOutboxPublisherTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-18T16:00:00Z");
    private static final LocalDateTime FIXED_TIME = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String WORKER = "outbox-worker-test";

    @Test
    public void shouldMarkPublishedOnlyAfterKafkaAck() throws Exception {
        Fixture fixture = fixture(true);
        RagOutboxPO event = event(1, 3, 7L);
        when(fixture.dao.queryDueCandidates(FIXED_TIME, 20)).thenReturn(List.of(candidate()));
        when(fixture.claimService.claim(any(), eq(WORKER), eq(FIXED_TIME), any()))
                .thenReturn(Optional.of(event));
        CompletableFuture<SendResult<String, String>> ack =
                CompletableFuture.completedFuture(mock(SendResult.class));
        when(fixture.kafka.send("rag.ingest.request.v1", "tenant-a:task-1", "{\"taskId\":\"task-1\"}"))
                .thenReturn(ack);
        when(fixture.dao.markPublished("tenant-a", "event-1", WORKER, 7L, FIXED_TIME)).thenReturn(1);

        fixture.publisher.publishDueBatch();

        verify(fixture.kafka).send("rag.ingest.request.v1", "tenant-a:task-1", "{\"taskId\":\"task-1\"}");
        verify(fixture.dao).markPublished("tenant-a", "event-1", WORKER, 7L, FIXED_TIME);
        verify(fixture.dao, never()).markRetrying(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), anyString());
    }

    @Test
    public void shouldScheduleSanitizedExponentialRetryAfterKafkaFailure() {
        Fixture fixture = fixture(true);
        RagOutboxPO event = event(2, 4, 8L);
        when(fixture.dao.queryDueCandidates(FIXED_TIME, 20)).thenReturn(List.of(candidate()));
        when(fixture.claimService.claim(any(), eq(WORKER), eq(FIXED_TIME), any()))
                .thenReturn(Optional.of(event));
        when(fixture.kafka.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("secret-token=must-not-leak")));
        when(fixture.dao.markRetrying(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), anyString())).thenReturn(1);

        fixture.publisher.publishDueBatch();

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(fixture.dao).markRetrying(eq("tenant-a"), eq("event-1"), eq(WORKER), eq(8L),
                eq(FIXED_TIME), retryAt.capture(), error.capture());
        Assert.assertEquals(FIXED_TIME.plusNanos(200_000_000L), retryAt.getValue());
        Assert.assertEquals("KAFKA_PUBLISH_FAILED:IllegalStateException", error.getValue());
        Assert.assertFalse(error.getValue().contains("secret-token"));
    }

    @Test
    public void shouldNotOverwriteNewOwnerWhenPublishedFenceIsStale() {
        Fixture fixture = fixture(true);
        RagOutboxPO event = event(1, 3, 9L);
        when(fixture.dao.queryDueCandidates(FIXED_TIME, 20)).thenReturn(List.of(candidate()));
        when(fixture.claimService.claim(any(), eq(WORKER), eq(FIXED_TIME), any()))
                .thenReturn(Optional.of(event));
        when(fixture.kafka.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.completedFuture(mock(SendResult.class)));
        when(fixture.dao.markPublished("tenant-a", "event-1", WORKER, 9L, FIXED_TIME)).thenReturn(0);

        fixture.publisher.publishDueBatch();

        verify(fixture.dao).markPublished("tenant-a", "event-1", WORKER, 9L, FIXED_TIME);
        verify(fixture.dao, never()).markRetrying(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), anyString());
        verify(fixture.dao, never()).markDead(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any(), anyString());
    }

    @Test
    public void shouldMoveExhaustedFailureToDead() {
        Fixture fixture = fixture(true);
        RagOutboxPO event = event(3, 3, 10L);
        when(fixture.dao.queryDueCandidates(FIXED_TIME, 20)).thenReturn(List.of(candidate()));
        when(fixture.claimService.claim(any(), eq(WORKER), eq(FIXED_TIME), any()))
                .thenReturn(Optional.of(event));
        when(fixture.kafka.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("database-password")));
        when(fixture.dao.markDead(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any(), anyString())).thenReturn(1);

        fixture.publisher.publishDueBatch();

        verify(fixture.dao).markDead("tenant-a", "event-1", WORKER, 10L,
                FIXED_TIME, "KAFKA_PUBLISH_FAILED:RuntimeException");
        verify(fixture.dao, never()).markRetrying(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), anyString());
    }

    @Test
    public void shouldDoNothingWhenPublisherIsDisabled() {
        Fixture fixture = fixture(false);

        fixture.publisher.publishDueBatch();

        verifyNoInteractions(fixture.dao, fixture.claimService, fixture.kafka);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(boolean enabled) {
        IRagOutboxDao dao = mock(IRagOutboxDao.class);
        RagOutboxClaimService claimService = mock(RagOutboxClaimService.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        RagProperties properties = new RagProperties();
        properties.getOutbox().setEnabled(enabled);
        properties.getOutbox().setRetryBaseDelayMs(100L);
        properties.getOutbox().setRetryMaxDelayMs(10000L);
        properties.getOutbox().setRetryJitterRatio(0.2D);
        RagOutboxPublisher publisher = new RagOutboxPublisher(dao, claimService, kafka, properties,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), () -> 0.5D, WORKER);
        return new Fixture(dao, claimService, kafka, publisher);
    }

    private RagOutboxCandidatePO candidate() {
        RagOutboxCandidatePO candidate = new RagOutboxCandidatePO();
        candidate.setTenantId("tenant-a");
        candidate.setEventId("event-1");
        return candidate;
    }

    private RagOutboxPO event(int attemptCount, int maxAttempts, long fencingToken) {
        return RagOutboxPO.builder().tenantId("tenant-a").eventId("event-1")
                .topicName("rag.ingest.request.v1").partitionKey("tenant-a:task-1")
                .payload("{\"taskId\":\"task-1\"}").attemptCount(attemptCount)
                .maxAttempts(maxAttempts).fencingToken(fencingToken).build();
    }

    private record Fixture(IRagOutboxDao dao, RagOutboxClaimService claimService,
                           KafkaTemplate<String, String> kafka, RagOutboxPublisher publisher) {
    }
}
