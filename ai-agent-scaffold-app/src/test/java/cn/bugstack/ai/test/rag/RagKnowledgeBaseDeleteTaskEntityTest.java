package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStatus;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

/** 知识库级联删除任务的租约、栅栏和检查点不变量。 */
public class RagKnowledgeBaseDeleteTaskEntityTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    public void shouldClaimAdvanceAndCompleteOnlyAfterVerification() {
        RagKnowledgeBaseDeleteTaskEntity running = pending().claim(
                "worker-a", 1L, NOW, Duration.ofSeconds(30));
        Assert.assertEquals(RagKnowledgeBaseDeleteStatus.RUNNING, running.status());
        Assert.assertEquals(1, running.attemptCount());

        RagKnowledgeBaseDeleteTaskEntity deleting = running.advance("worker-a", 1L, NOW,
                new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS,
                        2, 1, "doc-2"));
        AppException premature = Assert.assertThrows(AppException.class,
                () -> deleting.complete("worker-a", 1L, NOW));
        Assert.assertEquals("RAG_KB_DELETE_NOT_VERIFIED", premature.getCode());

        RagKnowledgeBaseDeleteTaskEntity verifying = deleting.advance("worker-a", 1L, NOW,
                new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.VERIFYING,
                        2, 2, null));
        RagKnowledgeBaseDeleteTaskEntity completed = verifying.complete("worker-a", 1L, NOW);
        Assert.assertEquals(RagKnowledgeBaseDeleteStatus.COMPLETED, completed.status());
        Assert.assertEquals(RagKnowledgeBaseDeleteStage.COMPLETED, completed.checkpoint().stage());
        Assert.assertNull(completed.lease());
    }

    @Test
    public void shouldRejectStaleFenceAndCheckpointRegression() {
        RagKnowledgeBaseDeleteTaskEntity running = pending().claim(
                "worker-a", 4L, NOW, Duration.ofSeconds(30));

        AppException stale = Assert.assertThrows(AppException.class,
                () -> running.advance("worker-a", 3L, NOW,
                        new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS,
                                2, 0, "doc-1")));
        Assert.assertEquals("RAG_KB_DELETE_FENCE_LOST", stale.getCode());

        RagKnowledgeBaseDeleteTaskEntity deleting = running.advance("worker-a", 4L, NOW,
                new RagKnowledgeBaseDeleteCheckpoint(RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS,
                        2, 1, "doc-2"));
        AppException regression = Assert.assertThrows(AppException.class,
                () -> deleting.advance("worker-a", 4L, NOW,
                        RagKnowledgeBaseDeleteCheckpoint.initial(2)));
        Assert.assertEquals("RAG_KB_DELETE_CHECKPOINT_REGRESSION", regression.getCode());
    }

    @Test
    public void shouldRetryThenBecomeDeadAndAllowManualRequeue() {
        RagKnowledgeBaseDeleteTaskEntity first = pending().claim(
                "worker-a", 1L, NOW, Duration.ofSeconds(30));
        RagKnowledgeBaseDeleteTaskEntity retrying = first.fail("worker-a", 1L, NOW,
                true, NOW.plusSeconds(5), "REMOTE_TIMEOUT", "reranker\n timeout");
        Assert.assertEquals(RagKnowledgeBaseDeleteStatus.RETRYING, retrying.status());
        Assert.assertEquals("reranker timeout", retrying.errorMessage());

        RagKnowledgeBaseDeleteTaskEntity second = retrying.claim(
                "worker-b", 2L, NOW.plusSeconds(5), Duration.ofSeconds(30));
        RagKnowledgeBaseDeleteTaskEntity dead = second.fail("worker-b", 2L, NOW.plusSeconds(5),
                true, NOW.plusSeconds(10), "REMOTE_TIMEOUT", "again");
        Assert.assertEquals(RagKnowledgeBaseDeleteStatus.DEAD, dead.status());

        RagKnowledgeBaseDeleteTaskEntity requeued = dead.requeue();
        Assert.assertEquals(RagKnowledgeBaseDeleteStatus.PENDING, requeued.status());
        Assert.assertEquals(0, requeued.attemptCount());
        Assert.assertEquals(2L, requeued.fencingToken());
        Assert.assertEquals(dead.checkpoint(), requeued.checkpoint());
    }

    @Test
    public void shouldWaitForChildWithoutConsumingAttemptBudget() {
        RagKnowledgeBaseDeleteTaskEntity running = pending().claim(
                "worker-a", 1L, NOW, Duration.ofSeconds(30));
        RagKnowledgeBaseDeleteCheckpoint deleting = new RagKnowledgeBaseDeleteCheckpoint(
                RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS, 2, 0, "doc-1");

        RagKnowledgeBaseDeleteTaskEntity waiting = running.waitForChild(
                "worker-a", 1L, NOW, NOW.plusSeconds(2), deleting);
        RagKnowledgeBaseDeleteTaskEntity reclaimed = waiting.claim(
                "worker-b", 2L, NOW.plusSeconds(2), Duration.ofSeconds(30));

        Assert.assertEquals(RagKnowledgeBaseDeleteStatus.WAITING, waiting.status());
        Assert.assertEquals(1, waiting.attemptCount());
        Assert.assertEquals(1, reclaimed.attemptCount());
        Assert.assertEquals(2L, reclaimed.fencingToken());
    }

    private RagKnowledgeBaseDeleteTaskEntity pending() {
        return RagKnowledgeBaseDeleteTaskEntity.pending(
                "tenant-a", "kb-a", "owner-a", "task-a", "a".repeat(64), 2, 2);
    }
}
