package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentDeletionRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

/** 文档、版本和删除任务的独立不可逆状态机测试。 */
public class RagDocumentDeletionStateTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    public void shouldTombstoneAndCloseReadyDocument() {
        RagDocumentEntity ready = document(RagDocumentStatus.READY);

        RagDocumentEntity deleting = ready.requestDeletion();
        RagDocumentEntity deleted = deleting.deleted();

        Assert.assertEquals(RagDocumentStatus.DELETING, deleting.status());
        Assert.assertEquals("ver-a", deleting.activeVersionId());
        Assert.assertEquals(RagDocumentStatus.DELETED, deleted.status());
        Assert.assertNull(deleted.activeVersionId());
        Assert.assertEquals(0L, deleted.activeGeneration());
        Assert.assertSame(deleting, deleting.requestDeletion());
        Assert.assertSame(deleted, deleted.deleted());
    }

    @Test
    public void shouldRejectDeletionWhileDocumentOrVersionIsProcessing() {
        assertCode("RAG_DOCUMENT_BUSY", () -> document(RagDocumentStatus.PROCESSING).requestDeletion());
        assertCode("RAG_DOCUMENT_VERSION_BUSY",
                () -> version(RagDocumentVersionStatus.PROCESSING).requestDeletion());
    }

    @Test
    public void shouldDeleteReadyVersionWithoutReusingTerminalCancellation() {
        RagDocumentVersionEntity ready = version(RagDocumentVersionStatus.READY);

        RagDocumentVersionEntity deleting = ready.requestDeletion();
        RagDocumentVersionEntity deleted = deleting.deleted();

        Assert.assertEquals(RagDocumentVersionStatus.DELETING, deleting.status());
        Assert.assertEquals(RagDocumentVersionStatus.DELETED, deleted.status());
        Assert.assertEquals("parsed/key.json", deleted.parsedObjectKey());
    }

    @Test
    public void shouldAdvanceDeleteStagesCompleteWithZeroChunksAndRejectCancel() {
        RagIngestJobEntity running = deleteTask().claim("worker-a", 1L, NOW, Duration.ofMinutes(1));
        assertCode("RAG_DELETE_NOT_CANCELLABLE", () -> running.requestCancel("撤销删除"));

        RagIngestJobEntity job = running.advanceDeletion(
                "worker-a", 1L, NOW.plusSeconds(1), RagIngestStage.DELETING_VECTORS);
        job = job.advanceDeletion("worker-a", 1L, NOW.plusSeconds(2), RagIngestStage.DELETING_CHUNKS);
        job = job.advanceDeletion("worker-a", 1L, NOW.plusSeconds(3), RagIngestStage.DELETING_SOURCE);
        job = job.completeDeletion("worker-a", 1L, NOW.plusSeconds(4));

        Assert.assertEquals(RagIngestJobStatus.COMPLETED, job.status());
        Assert.assertEquals(RagIngestStage.COMPLETED, job.checkpoint().stage());
        Assert.assertEquals(0, job.checkpoint().totalChunks());
    }

    @Test
    public void shouldRequeueFailedDeleteAtSameCheckpoint() {
        RagIngestJobEntity running = deleteTask().claim("worker-a", 1L, NOW, Duration.ofMinutes(1));
        RagIngestJobEntity deleting = running.advanceDeletion(
                "worker-a", 1L, NOW.plusSeconds(1), RagIngestStage.DELETING_VECTORS);
        RagIngestJobEntity failed = deleting.failTerminal(
                "worker-a", 1L, NOW.plusSeconds(2), "OBJECT_STORAGE_DELETE_FAILED", "对象删除失败");

        RagIngestJobEntity requeued = failed.requeueDeletion();

        Assert.assertEquals(RagIngestJobStatus.PENDING, requeued.status());
        Assert.assertEquals(RagIngestStage.DELETING_VECTORS, requeued.checkpoint().stage());
        Assert.assertEquals(0, requeued.attemptCount());
        Assert.assertNull(requeued.errorCode());
    }

    @Test
    public void shouldRejectHalfParsedLocatorAndCrossDocumentDeleteRegistration() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new RagDocumentVersionEntity(
                "tenant-a", "kb-a", "doc-a", "ver-a", 1, 3L,
                "rag", "source/document.md", "rag", null, "document.md", "a".repeat(64),
                "text/markdown", 100L, RagDocumentVersionStatus.READY,
                null, null, null, 1L));

        RagDocumentEntity deleting = document(RagDocumentStatus.READY).requestDeletion();
        RagDocumentVersionEntity deletingVersion = version(RagDocumentVersionStatus.READY).requestDeletion();
        RagIngestJobEntity wrongDocument = RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-b", "ver-a",
                "task-delete", "delete-key", RagIngestOperation.DELETE, 3L, 3);
        Assert.assertThrows(IllegalArgumentException.class, () -> new RagDocumentDeletionRegistration(
                deleting, java.util.List.of(deletingVersion), wrongDocument, "event-a"));
    }

    private RagDocumentEntity document(RagDocumentStatus status) {
        return new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT, "kb-a", "doc-a",
                "document.md", "ver-a", 3L, null, status, 7L);
    }

    private RagDocumentVersionEntity version(RagDocumentVersionStatus status) {
        return new RagDocumentVersionEntity("tenant-a", "kb-a", "doc-a", "ver-a", 1, 3L,
                "rag-source", "source/document.md", "rag-parsed", "parsed/key.json",
                "document.md", "a".repeat(64), "text/markdown", 100L, status,
                "parser-v1", "chunk-v1", "embed-v1", 5L);
    }

    private RagIngestJobEntity deleteTask() {
        return RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-a", "ver-a", "task-delete",
                "delete-key", RagIngestOperation.DELETE, 3L, 3);
    }

    private void assertCode(String code, Runnable action) {
        AppException error = Assert.assertThrows(AppException.class, action::run);
        Assert.assertEquals(code, error.getCode());
    }
}
