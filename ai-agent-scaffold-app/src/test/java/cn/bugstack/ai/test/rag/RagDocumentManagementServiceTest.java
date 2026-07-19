package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagDocumentManagementService;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 摄取任务取消状态与 CAS 次数测试。 */
public class RagDocumentManagementServiceTest {

    @Test
    public void shouldListBoundedKnowledgeBaseTasksAfterAdministratorAuthorization() {
        IRagRepository repository = repository();
        when(repository.listIngestJobs("tenant-a", "kb-a", 50)).thenReturn(List.of(pending()));

        List<RagIngestJobEntity> result = service(repository).listTasks(
                "tenant-a", "admin-a", "admin", "kb-a", 50);

        Assert.assertEquals(1, result.size());
        Assert.assertEquals("task-a", result.get(0).jobId());
        verify(repository).listIngestJobs("tenant-a", "kb-a", 50);
    }

    @Test
    public void shouldRejectInvalidTaskListLimitBeforeRepositoryQuery() {
        IRagRepository repository = repository();

        AppException error = Assert.assertThrows(AppException.class, () -> service(repository).listTasks(
                "tenant-a", "admin-a", "admin", "kb-a", 201));

        Assert.assertEquals("RAG_TASK_LIMIT_INVALID", error.getCode());
        verify(repository, never()).listIngestJobs(any(), any(), anyInt());
    }

    @Test
    public void shouldSynchronouslyCancelUnleasedPendingTaskWithOneLifecycleTransaction() {
        IRagRepository repository = repository();
        RagIngestJobEntity pending = pending();
        RagIngestJobEntity requested = pending.requestCancel("用户撤回");
        RagIngestJobEntity cancelled = requested.markCancelled(null, requested.fencingToken(), Instant.now());
        when(repository.findIngestJob("tenant-a", "task-a"))
                .thenReturn(Optional.of(pending), Optional.of(requested), Optional.of(cancelled));
        when(repository.updateIngestJob(eq("tenant-a"), any(), anyLong())).thenReturn(1);
        stubDocumentLifecycle(repository);

        RagIngestJobEntity result = service(repository).cancelTask(
                "tenant-a", "admin-a", "admin", "task-a", "用户撤回");

        Assert.assertEquals("CANCELLED", result.status().name());
        verify(repository, times(1)).updateIngestJob(eq("tenant-a"), any(), anyLong());
        verify(repository).cancelUnclaimedIngestJob(eq("tenant-a"), any(), eq(requested.revision()),
                eq(0L), eq(0L));
    }

    @Test
    public void shouldRepairLegacyCancelledTaskWithOpenDocumentLifecycle() {
        IRagRepository repository = repository();
        RagIngestJobEntity requested = pending().requestCancel("用户撤回");
        RagIngestJobEntity cancelled = requested.markCancelled(null, requested.fencingToken(), Instant.now());
        when(repository.findIngestJob("tenant-a", "task-a"))
                .thenReturn(Optional.of(cancelled), Optional.of(cancelled));
        stubDocumentLifecycle(repository);

        RagIngestJobEntity result = service(repository).cancelTask(
                "tenant-a", "admin-a", "admin", "task-a", "再次确认");

        Assert.assertEquals("CANCELLED", result.status().name());
        verify(repository).cancelUnclaimedIngestJob(eq("tenant-a"), eq(cancelled),
                eq(cancelled.revision()), eq(0L), eq(0L));
    }

    @Test
    public void shouldOnlySetBarrierForRunningTaskWithActiveLease() {
        IRagRepository repository = repository();
        Instant now = Instant.now();
        RagIngestJobEntity running = pending().claim("worker-a", 1, now, Duration.ofMinutes(2));
        RagIngestJobEntity requested = running.requestCancel("停止处理");
        when(repository.findIngestJob("tenant-a", "task-a"))
                .thenReturn(Optional.of(running), Optional.of(requested));
        when(repository.updateIngestJob(eq("tenant-a"), any(), anyLong())).thenReturn(1);

        RagIngestJobEntity result = service(repository).cancelTask(
                "tenant-a", "owner-a", "owner", "task-a", "停止处理");

        Assert.assertEquals("CANCEL_REQUESTED", result.status().name());
        Assert.assertNotNull(result.lease());
        verify(repository, times(1)).updateIngestJob(eq("tenant-a"), any(), anyLong());
    }

    @Test
    public void shouldRejectMemberBeforeTaskMutation() {
        IRagRepository repository = repository();
        when(repository.findIngestJob("tenant-a", "task-a")).thenReturn(Optional.of(pending()));
        try {
            service(repository).cancelTask("tenant-a", "member-a", "member", "task-a", null);
            Assert.fail("普通成员不能取消摄取任务");
        } catch (AppException e) {
            Assert.assertEquals("RAG_ADMIN_REQUIRED", e.getCode());
        }
    }

    @Test
    public void shouldAtomicallyRequeueFailedIngestVersionAndDocument() {
        IRagRepository repository = repository();
        RagIngestJobEntity failed = failed(RagIngestOperation.INGEST);
        RagIngestJobEntity requeued = failed.requeueIngest();
        when(repository.findIngestJob("tenant-a", "task-a"))
                .thenReturn(Optional.of(failed), Optional.of(requeued));
        when(repository.findDocumentVersion("tenant-a", "ver-a")).thenReturn(Optional.of(failedVersion()));
        when(repository.findDocument("tenant-a", "doc-a")).thenReturn(Optional.of(failedDocument()));

        RagIngestJobEntity result = service(repository).retryTask(
                "tenant-a", "admin-a", "admin", "task-a");

        Assert.assertEquals(RagIngestJobStatus.PENDING, result.status());
        Assert.assertEquals(0, result.attemptCount());
        verify(repository).requeueFailedIngestJob(eq("tenant-a"), any(), eq(failed.revision()),
                any(), eq(0L), any(), eq(0L), eq(0L));
        verify(repository, never()).insertIngestJob(any(), any());
    }

    @Test
    public void shouldResumeFailedDeleteCheckpointThroughUnifiedRetry() {
        IRagRepository repository = repository();
        RagIngestJobEntity failed = failed(RagIngestOperation.DELETE);
        RagIngestJobEntity requeued = failed.requeueDeletion();
        when(repository.findIngestJob("tenant-a", "task-a"))
                .thenReturn(Optional.of(failed), Optional.of(requeued));
        when(repository.findDocument("tenant-a", "doc-a")).thenReturn(Optional.of(
                new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT, "kb-a", "doc-a",
                        "document.md", "ver-a", 1, null, RagDocumentStatus.DELETING, 3)));
        when(repository.updateIngestJob("tenant-a", requeued, failed.revision())).thenReturn(1);

        RagIngestJobEntity result = service(repository).retryTask(
                "tenant-a", "owner-a", "owner", "task-a");

        Assert.assertEquals(RagIngestJobStatus.PENDING, result.status());
        verify(repository).updateIngestJob("tenant-a", requeued, failed.revision());
        verify(repository, never()).requeueFailedIngestJob(any(), any(), anyLong(), any(), anyLong(),
                any(), anyLong(), anyLong());
    }

    @Test
    public void shouldRejectRetryForActiveCancelledAndRebuildTasks() {
        IRagRepository activeRepository = repository();
        when(activeRepository.findIngestJob("tenant-a", "task-a")).thenReturn(Optional.of(pending()));
        assertAppException("RAG_INGEST_RETRY_STATE_INVALID", () -> service(activeRepository).retryTask(
                "tenant-a", "admin-a", "admin", "task-a"));

        IRagRepository rebuildRepository = repository();
        when(rebuildRepository.findIngestJob("tenant-a", "task-a"))
                .thenReturn(Optional.of(failed(RagIngestOperation.REBUILD)));
        assertAppException("RAG_REBUILD_NOT_IMPLEMENTED", () -> service(rebuildRepository).retryTask(
                "tenant-a", "admin-a", "admin", "task-a"));

        IRagRepository memberRepository = repository();
        when(memberRepository.findIngestJob("tenant-a", "task-a"))
                .thenReturn(Optional.of(failed(RagIngestOperation.INGEST)));
        assertAppException("RAG_ADMIN_REQUIRED", () -> service(memberRepository).retryTask(
                "tenant-a", "member-a", "member", "task-a"));
        verify(memberRepository, never()).requeueFailedIngestJob(any(), any(), anyLong(), any(), anyLong(),
                any(), anyLong(), anyLong());
    }

    private IRagRepository repository() {
        IRagRepository repository = mock(IRagRepository.class);
        when(repository.findKnowledgeBase("tenant-a", "kb-a")).thenReturn(Optional.of(
                new RagKnowledgeBaseEntity("tenant-a", "owner-a", "kb-a", "知识库", null,
                        RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null,
                        768, "collection", 0, 0)));
        return repository;
    }

    private RagDocumentManagementService service(IRagRepository repository) {
        return new RagDocumentManagementService(repository, new RagKnowledgeBaseAuthorizationService());
    }

    private void stubDocumentLifecycle(IRagRepository repository) {
        when(repository.findDocumentVersion("tenant-a", "ver-a")).thenReturn(Optional.of(
                new RagDocumentVersionEntity("tenant-a", "kb-a", "doc-a", "ver-a", 1, 1,
                        "rag", "object", null, null, "document.md", "sha256", "text/markdown", 10,
                        RagDocumentVersionStatus.QUEUED, null, null, null, 0)));
        when(repository.findDocument("tenant-a", "doc-a")).thenReturn(Optional.of(
                new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT, "kb-a", "doc-a",
                        "document.md", null, 0, 1L, RagDocumentStatus.PROCESSING, 0)));
    }

    private RagIngestJobEntity pending() {
        return RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-a", "ver-a", "task-a",
                "task-key", RagIngestOperation.INGEST, 1, 3);
    }

    private RagIngestJobEntity failed(RagIngestOperation operation) {
        Instant now = Instant.parse("2026-07-20T08:00:00Z");
        return RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-a", "ver-a", "task-a",
                        "task-key", operation, 1, 3)
                .claim("worker-a", 1, now, Duration.ofMinutes(1))
                .failTerminal("worker-a", 1, now.plusSeconds(1), "RAG_TEST_FAILURE", "测试失败");
    }

    private RagDocumentVersionEntity failedVersion() {
        return new RagDocumentVersionEntity("tenant-a", "kb-a", "doc-a", "ver-a", 1, 1,
                "rag", "object", "rag", "parsed", "document.md", "sha256", "text/markdown", 10,
                RagDocumentVersionStatus.FAILED, "parser", "chunker", "embedding", 0,
                2, 100, 3, java.util.Map.of("old", "metric"));
    }

    private RagDocumentEntity failedDocument() {
        return new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT, "kb-a", "doc-a",
                "document.md", null, 0, null, RagDocumentStatus.FAILED, 0);
    }

    private void assertAppException(String code, Runnable action) {
        AppException error = Assert.assertThrows(AppException.class, action::run);
        Assert.assertEquals(code, error.getCode());
    }
}
