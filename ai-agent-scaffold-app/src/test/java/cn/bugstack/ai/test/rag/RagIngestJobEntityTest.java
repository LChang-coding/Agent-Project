package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * 摄取任务取消、租约、fencing 与检查点状态机测试。
 */
public class RagIngestJobEntityTest {

    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    public void shouldClaimAdvanceAndCompleteWithCurrentFence() {
        RagIngestJobEntity job = pending(3).claim("worker-a", 1L, NOW, LEASE);
        Assert.assertEquals(RagIngestJobStatus.RUNNING, job.status());
        Assert.assertEquals(1, job.attemptCount());

        job = job.advance("worker-a", 1L, NOW.plusSeconds(1),
                checkpoint(RagIngestStage.PARSING, 0, 0, 0, 0));
        job = job.advance("worker-a", 1L, NOW.plusSeconds(2),
                checkpoint(RagIngestStage.CHUNKING, 0, 3, 0, 0));
        job = job.advance("worker-a", 1L, NOW.plusSeconds(3),
                checkpoint(RagIngestStage.EMBEDDING, 1, 3, 1, 0));
        job = job.advance("worker-a", 1L, NOW.plusSeconds(4),
                checkpoint(RagIngestStage.INDEXING, 3, 3, 2, 1));
        job = job.advance("worker-a", 1L, NOW.plusSeconds(5),
                checkpoint(RagIngestStage.VERIFYING, 3, 3, 2, 3));
        job = job.complete("worker-a", 1L, NOW.plusSeconds(6));

        Assert.assertEquals(RagIngestJobStatus.COMPLETED, job.status());
        Assert.assertEquals(RagIngestStage.COMPLETED, job.checkpoint().stage());
        Assert.assertNull(job.lease());
    }

    @Test
    public void shouldRejectCheckpointRegressionAndStageSkipping() {
        RagIngestJobEntity running = pending(3).claim("worker-a", 1L, NOW, LEASE);
        assertAppException("RAG_INGEST_CHECKPOINT_REGRESSION", () -> running.advance("worker-a", 1L,
                NOW.plusSeconds(1), checkpoint(RagIngestStage.EMBEDDING, 0, 3, 0, 0)));

        RagIngestJobEntity parsing = running.advance("worker-a", 1L, NOW.plusSeconds(1),
                checkpoint(RagIngestStage.PARSING, 0, 0, 0, 0));
        assertAppException("RAG_INGEST_CHECKPOINT_REGRESSION", () -> parsing.advance("worker-a", 1L,
                NOW.plusSeconds(2), checkpoint(RagIngestStage.RECEIVED, 0, 0, 0, 0)));
    }

    @Test
    public void shouldFenceStaleWorkerAfterExpiredLeaseTakeover() {
        RagIngestJobEntity first = pending(3).claim("worker-a", 1L, NOW, LEASE);
        assertAppException("RAG_INGEST_LEASE_ACTIVE",
                () -> first.claim("worker-b", 2L, NOW.plusSeconds(29), LEASE));

        RagIngestJobEntity takeover = first.claim("worker-b", 2L, NOW.plusSeconds(30), LEASE);
        assertAppException("RAG_INGEST_FENCE_STALE",
                () -> takeover.assertExternalCallAllowed("worker-a", 1L, NOW.plusSeconds(31)));
        takeover.assertExternalCallAllowed("worker-b", 2L, NOW.plusSeconds(31));
    }

    @Test
    public void shouldBlockExternalCallsImmediatelyAfterCancellationRequest() {
        RagIngestJobEntity running = pending(3).claim("worker-a", 1L, NOW, LEASE);
        RagIngestJobEntity cancelling = running.requestCancel("用户取消");

        Assert.assertEquals(RagIngestJobStatus.CANCEL_REQUESTED, cancelling.status());
        assertAppException("RAG_INGEST_SIDE_EFFECT_BLOCKED",
                () -> cancelling.assertExternalCallAllowed("worker-a", 1L, NOW.plusSeconds(1)));

        RagIngestJobEntity cancelled = cancelling.markCancelled("worker-a", 1L, NOW.plusSeconds(1));
        Assert.assertEquals(RagIngestJobStatus.CANCELLED, cancelled.status());
        Assert.assertSame(cancelled, cancelled.requestCancel("重复取消"));
    }

    @Test
    public void shouldRetryUntilAttemptBudgetIsExhausted() {
        RagIngestJobEntity first = pending(2).claim("worker-a", 1L, NOW, LEASE);
        RagIngestJobEntity retrying = first.failRetryable("worker-a", 1L, NOW.plusSeconds(1),
                NOW.plusSeconds(10), "DOCLING_TIMEOUT", "解析超时");
        Assert.assertEquals(RagIngestJobStatus.RETRYING, retrying.status());

        RagIngestJobEntity second = retrying.claim("worker-b", 2L, NOW.plusSeconds(10), LEASE);
        RagIngestJobEntity dead = second.failRetryable("worker-b", 2L, NOW.plusSeconds(11),
                NOW.plusSeconds(20), "EMBEDDING_TIMEOUT", "向量化超时");

        Assert.assertEquals(RagIngestJobStatus.DEAD, dead.status());
        Assert.assertNull(dead.nextRetryAt());
        assertAppException("RAG_INGEST_NOT_CLAIMABLE",
                () -> dead.claim("worker-c", 3L, NOW.plusSeconds(30), LEASE));
    }

    @Test
    public void shouldKeepTerminalFailureNonTerminalUntilCleanupCompletes() {
        RagIngestJobEntity running = pending(2).claim("worker-a", 1L, NOW, LEASE);
        RagIngestJobEntity cleanup = running.requestFailureCleanup(
                true, "RAG_QDRANT_UNAVAILABLE", "向量索引阶段失败");

        Assert.assertEquals(RagIngestJobStatus.CANCEL_REQUESTED, cleanup.status());
        Assert.assertEquals(RagIngestJobEntity.FAILURE_CLEANUP_DEAD, cleanup.cancelReason());
        RagIngestJobEntity dead = cleanup.markFailedAfterCleanup(
                "worker-a", 1L, NOW.plusSeconds(1));

        Assert.assertEquals(RagIngestJobStatus.DEAD, dead.status());
        Assert.assertNull(dead.lease());
        Assert.assertEquals("RAG_QDRANT_UNAVAILABLE", dead.errorCode());
    }

    @Test
    public void shouldRequireVerificationBeforeCompletion() {
        RagIngestJobEntity running = pending(2).claim("worker-a", 1L, NOW, LEASE);
        assertAppException("RAG_INGEST_NOT_VERIFIED",
                () -> running.complete("worker-a", 1L, NOW.plusSeconds(1)));
    }

    @Test
    public void shouldRejectIncompleteVerifiedCheckpoint() {
        try {
            checkpoint(RagIngestStage.VERIFYING, 2, 3, 1, 2);
            Assert.fail("预期拒绝不完整验证检查点");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("检查点"));
        }
    }

    @Test
    public void shouldRetainOperationAndGenerationAcrossTransitions() {
        RagIngestJobEntity running = pending(2).claim("worker-a", 1L, NOW, LEASE);

        Assert.assertEquals(RagIngestOperation.REBUILD, running.operation());
        Assert.assertEquals(7L, running.generation());
    }

    @Test
    public void shouldRequeueFailedIngestFromInitialCheckpointAndResetAttempts() {
        RagIngestJobEntity running = ingestPending(3).claim("worker-a", 1L, NOW, LEASE);
        RagIngestJobEntity parsing = running.advance("worker-a", 1L, NOW.plusSeconds(1),
                checkpoint(RagIngestStage.PARSING, 0, 0, 0, 0));
        RagIngestJobEntity failed = parsing.failTerminal("worker-a", 1L, NOW.plusSeconds(2),
                "RAG_DOCLING_FAILED", "解析失败");

        RagIngestJobEntity requeued = failed.requeueIngest();

        Assert.assertEquals(RagIngestJobStatus.PENDING, requeued.status());
        Assert.assertEquals(RagIngestStage.RECEIVED, requeued.checkpoint().stage());
        Assert.assertEquals(0, requeued.attemptCount());
        Assert.assertNull(requeued.errorCode());
        Assert.assertEquals(failed.fencingToken(), requeued.fencingToken());
        assertAppException("RAG_INGEST_REQUEUE_STATE_INVALID", () -> ingestPending(3).requeueIngest());
    }

    private RagIngestJobEntity pending(int maxAttempts) {
        return RagIngestJobEntity.pending("tenant-a", "kb-1", "doc-1", "version-1",
                "job-1", "tenant-a:doc-1:version-1", RagIngestOperation.REBUILD, 7L, maxAttempts);
    }

    private RagIngestJobEntity ingestPending(int maxAttempts) {
        return RagIngestJobEntity.pending("tenant-a", "kb-1", "doc-1", "version-1",
                "job-1", "tenant-a:doc-1:version-1", RagIngestOperation.INGEST, 7L, maxAttempts);
    }

    private RagIngestCheckpoint checkpoint(RagIngestStage stage, int processed, int total,
                                           int embeddingBatch, int vectorIndex) {
        return new RagIngestCheckpoint(stage, processed, total, embeddingBatch, vectorIndex);
    }

    private void assertAppException(String code, Runnable action) {
        try {
            action.run();
            Assert.fail("预期抛出领域异常：" + code);
        } catch (AppException e) {
            Assert.assertEquals(code, e.getCode());
        }
    }
}
