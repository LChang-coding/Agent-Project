package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
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
}
