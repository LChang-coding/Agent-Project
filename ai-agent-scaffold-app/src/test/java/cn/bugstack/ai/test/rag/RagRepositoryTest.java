package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagIndexActivation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.infrastructure.adapter.repository.RagRepository;
import cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao;
import cn.bugstack.ai.infrastructure.dao.IRagChunkDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagRetrievalProfileDao;
import cn.bugstack.ai.infrastructure.dao.po.RagIngestTaskPO;
import cn.bugstack.ai.infrastructure.dao.po.RagIngestCandidatePO;
import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBasePO;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceCodec;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * RAG Repository 租户参数和任务原子领取编排测试。
 */
public class RagRepositoryTest {

    private IRagKnowledgeBaseDao knowledgeBaseDao;
    private IRagIngestTaskDao ingestTaskDao;
    private IRagDocumentDao documentDao;
    private IRagDocumentVersionDao documentVersionDao;
    private RagRepository repository;

    @Before
    public void setUp() {
        knowledgeBaseDao = Mockito.mock(IRagKnowledgeBaseDao.class);
        ingestTaskDao = Mockito.mock(IRagIngestTaskDao.class);
        documentDao = Mockito.mock(IRagDocumentDao.class);
        documentVersionDao = Mockito.mock(IRagDocumentVersionDao.class);
        RagPersistenceCodec codec = new RagPersistenceCodec(new ObjectMapper());
        repository = new RagRepository(knowledgeBaseDao, documentDao,
                documentVersionDao, ingestTaskDao, Mockito.mock(IRagChunkDao.class),
                Mockito.mock(IRagRetrievalProfileDao.class), Mockito.mock(IRagAgentBindingDao.class),
                new RagPersistenceMapper(codec), codec);
    }

    @Test
    public void shouldPassTrustedTenantIntoKnowledgeBaseQuery() {
        Mockito.when(knowledgeBaseDao.queryByTenantAndKnowledgeBaseId("tenant-a", "kb-1"))
                .thenReturn(knowledgeBasePo());

        RagKnowledgeBaseEntity result = repository.findKnowledgeBase("tenant-a", "kb-1").orElseThrow();

        Assert.assertEquals("tenant-a", result.tenantId());
        Mockito.verify(knowledgeBaseDao).queryByTenantAndKnowledgeBaseId("tenant-a", "kb-1");
    }

    @Test
    public void shouldRejectEntityFromAnotherTenantBeforeInsert() {
        RagKnowledgeBaseEntity crossTenant = new RagKnowledgeBaseEntity("tenant-b", "owner-1", "kb-1",
                "企业知识库", null, RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE,
                "profile-1", 768, "rag-kb-1", 1L, 0L);

        try {
            repository.insertKnowledgeBase("tenant-a", crossTenant);
            Assert.fail("预期拒绝跨租户实体");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("租户"));
        }
        Mockito.verifyNoInteractions(knowledgeBaseDao);
    }

    @Test
    public void shouldReadClaimedTaskOnlyAfterAtomicTenantTaskUpdate() {
        Instant now = Instant.parse("2026-07-18T15:00:00Z");
        Instant leaseUntil = now.plusSeconds(30);
        Mockito.when(ingestTaskDao.claimDue("tenant-a", "job-1", "worker-a",
                        LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                        LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC)))
                .thenReturn(1);
        Mockito.when(ingestTaskDao.queryByTenantAndTaskId("tenant-a", "job-1"))
                .thenReturn(runningTask(leaseUntil));

        var claimed = repository.claimDueIngestJob(
                "tenant-a", "job-1", "worker-a", now, leaseUntil).orElseThrow();

        Assert.assertEquals("worker-a", claimed.lease().owner());
        Assert.assertEquals(1L, claimed.fencingToken());
        Mockito.verify(ingestTaskDao).queryByTenantAndTaskId("tenant-a", "job-1");
    }

    @Test
    public void shouldNotReadTaskWhenAtomicClaimLosesRace() {
        Instant now = Instant.parse("2026-07-18T15:00:00Z");
        Instant leaseUntil = now.plusSeconds(30);
        Mockito.when(ingestTaskDao.claimDue(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.any(), Mockito.any())).thenReturn(0);

        Assert.assertTrue(repository.claimDueIngestJob(
                "tenant-a", "job-1", "worker-b", now, leaseUntil).isEmpty());

        Mockito.verify(ingestTaskDao, Mockito.never())
                .queryByTenantAndTaskId(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void shouldExposeOnlyTenantAndJobFromGlobalDueScan() {
        Instant now = Instant.parse("2026-07-18T15:00:00Z");
        Mockito.when(ingestTaskDao.queryDueCandidates(LocalDateTime.ofInstant(now, ZoneOffset.UTC), 20))
                .thenReturn(List.of(new RagIngestCandidatePO("tenant-a", "job-1")));

        var candidates = repository.listDueIngestJobCandidates(now, 20);

        Assert.assertEquals(List.of("tenant-a", "job-1"),
                List.of(candidates.get(0).tenantId(), candidates.get(0).jobId()));
    }

    @Test
    public void shouldUseTenantRevisionOwnerAndFenceForWorkerCas() {
        Instant now = Instant.parse("2026-07-18T15:00:00Z");
        RagIngestJobEntity failed = terminalJob(RagIngestJobStatus.FAILED);
        Mockito.when(ingestTaskDao.updateClaimedByTenantFenceAndRevision(
                Mockito.eq("tenant-a"), Mockito.any(), Mockito.eq(7L), Mockito.eq("worker-a"),
                Mockito.eq(11L), Mockito.eq(LocalDateTime.ofInstant(now, ZoneOffset.UTC))))
                .thenReturn(0);

        Assert.assertEquals(0, repository.updateClaimedIngestJob(
                "tenant-a", failed, 7L, "worker-a", 11L, now));
        Mockito.verify(ingestTaskDao).updateClaimedByTenantFenceAndRevision(
                Mockito.eq("tenant-a"), Mockito.argThat(task -> "job-1".equals(task.getTaskId())),
                Mockito.eq(7L), Mockito.eq("worker-a"), Mockito.eq(11L), Mockito.any());
    }

    @Test
    public void shouldClaimCancelledTaskForCleanupWithoutReopeningIt() {
        Instant now = Instant.parse("2026-07-18T15:00:00Z");
        Instant leaseUntil = now.plusSeconds(30);
        Mockito.when(ingestTaskDao.claimCancelledForCleanup("tenant-a", "job-1", "worker-b",
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC))).thenReturn(1);
        RagIngestTaskPO task = runningTask(leaseUntil);
        task.setStatus("cancel_requested");
        task.setCancelReason("管理员取消");
        task.setLeaseOwner("worker-b");
        Mockito.when(ingestTaskDao.queryByTenantAndTaskId("tenant-a", "job-1")).thenReturn(task);

        var claimed = repository.claimCancelledIngestJobForCleanup(
                "tenant-a", "job-1", "worker-b", now, leaseUntil).orElseThrow();

        Assert.assertEquals(RagIngestJobStatus.CANCEL_REQUESTED, claimed.status());
        Assert.assertEquals("worker-b", claimed.lease().owner());
    }

    @Test
    public void shouldCompleteVersionDocumentKnowledgeBaseAndTaskAsOneLifecycle() {
        Instant now = Instant.parse("2026-07-18T15:00:00Z");
        RagIngestJobEntity completed = terminalJob(RagIngestJobStatus.COMPLETED);
        RagIndexActivation activation = new RagIndexActivation(
                "kb-1", "doc-1", "version-1", 2L, 3L, 4L, 5L);
        Mockito.when(documentVersionDao.markReadyByTenantAndRevision(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyLong(), Mockito.any())).thenReturn(1);
        Mockito.when(documentDao.activateVersionByTenantAndRevision(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyLong(), Mockito.any())).thenReturn(1);
        Mockito.when(knowledgeBaseDao.activateGenerationByTenantAndRevision(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong())).thenReturn(1);
        Mockito.when(ingestTaskDao.updateClaimedByTenantFenceAndRevision(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.any())).thenReturn(1);
        Mockito.when(ingestTaskDao.updateByTenantAndRevision(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong())).thenReturn(1);

        repository.completeClaimedIngestJob(
                "tenant-a", completed, 7L, "worker-a", 11L, activation, now);

        Mockito.verify(documentVersionDao).markReadyByTenantAndRevision(
                "tenant-a", "kb-1", "doc-1", "version-1", 2L, 3L,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        Mockito.verify(documentDao).activateVersionByTenantAndRevision(
                "tenant-a", "kb-1", "doc-1", "version-1", 2L, 4L,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        Mockito.verify(knowledgeBaseDao).activateGenerationByTenantAndRevision("tenant-a", "kb-1", 2L, 5L);
        Mockito.verify(ingestTaskDao).updateClaimedByTenantFenceAndRevision(
                Mockito.eq("tenant-a"), Mockito.any(), Mockito.eq(7L), Mockito.eq("worker-a"),
                Mockito.eq(11L), Mockito.any());
    }

    @Test
    public void shouldStopLifecycleWhenOldVersionRevisionLosesCas() {
        RagIngestJobEntity completed = terminalJob(RagIngestJobStatus.COMPLETED);
        RagIndexActivation activation = new RagIndexActivation(
                "kb-1", "doc-1", "version-1", 2L, 3L, 4L, 5L);
        Mockito.when(documentVersionDao.markReadyByTenantAndRevision(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyLong(), Mockito.any())).thenReturn(0);

        try {
            repository.completeClaimedIngestJob("tenant-a", completed, 7L, "worker-a", 11L,
                    activation, Instant.parse("2026-07-18T15:00:00Z"));
            Assert.fail("旧 revision 应导致 lifecycle 冲突");
        } catch (cn.bugstack.ai.types.exception.AppException expected) {
            Assert.assertEquals("RAG_LIFECYCLE_CONFLICT", expected.getCode());
        }
        Mockito.verifyNoInteractions(documentDao);
        Mockito.verify(ingestTaskDao, Mockito.never()).updateClaimedByTenantFenceAndRevision(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void shouldCloseCancelAndFailLifecyclesWithFencedTaskCas() {
        Instant now = Instant.parse("2026-07-18T15:00:00Z");
        Mockito.when(documentVersionDao.closeByTenantAndRevision(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong())).thenReturn(1);
        Mockito.when(documentDao.closeTargetGenerationByTenantAndRevision(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyLong())).thenReturn(1);
        Mockito.when(ingestTaskDao.cancelClaimedByTenantFenceAndRevision(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyLong())).thenReturn(1);
        Mockito.when(ingestTaskDao.updateClaimedByTenantFenceAndRevision(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.any())).thenReturn(1);
        Mockito.when(ingestTaskDao.updateByTenantAndRevision(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong())).thenReturn(1);

        repository.cancelClaimedIngestJob("tenant-a", terminalJob(RagIngestJobStatus.CANCELLED),
                7L, 3L, 4L, "worker-a", 11L, now);
        repository.failClaimedIngestJob("tenant-a", terminalJob(RagIngestJobStatus.FAILED),
                8L, 5L, 6L, "worker-a", 11L, now);
        repository.cancelUnclaimedIngestJob("tenant-a", terminalJob(RagIngestJobStatus.CANCELLED),
                9L, 9L, 10L);

        Mockito.verify(documentVersionDao).closeByTenantAndRevision(
                "tenant-a", "kb-1", "doc-1", "version-1", 2L, "cancelled", 3L);
        Mockito.verify(documentVersionDao).closeByTenantAndRevision(
                "tenant-a", "kb-1", "doc-1", "version-1", 2L, "failed", 5L);
        Mockito.verify(documentVersionDao).closeByTenantAndRevision(
                "tenant-a", "kb-1", "doc-1", "version-1", 2L, "cancelled", 9L);
        Mockito.verify(ingestTaskDao).cancelClaimedByTenantFenceAndRevision(
                Mockito.eq("tenant-a"), Mockito.any(), Mockito.eq(7L), Mockito.eq("worker-a"), Mockito.eq(11L));
        Mockito.verify(ingestTaskDao).updateClaimedByTenantFenceAndRevision(
                Mockito.eq("tenant-a"), Mockito.any(), Mockito.eq(8L), Mockito.eq("worker-a"),
                Mockito.eq(11L), Mockito.eq(LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
        Mockito.verify(ingestTaskDao).updateByTenantAndRevision(
                Mockito.eq("tenant-a"), Mockito.any(), Mockito.eq(9L));
    }

    private RagKnowledgeBasePO knowledgeBasePo() {
        return RagKnowledgeBasePO.builder().tenantId("tenant-a").ownerUserId("owner-1")
                .visibility("tenant_public").knowledgeBaseId("kb-1").knowledgeBaseName("企业知识库")
                .embeddingDimension(768).collectionAlias("rag-kb-1").currentGeneration(1L)
                .retrievalProfileId("profile-1").revision(0L).status("active").build();
    }

    private RagIngestTaskPO runningTask(Instant leaseUntil) {
        return RagIngestTaskPO.builder().taskId("job-1").taskKey("task-key-1").tenantId("tenant-a")
                .knowledgeBaseId("kb-1").documentId("doc-1").versionId("version-1").generation(1L)
                .operation("ingest").stage("received").status("running").attemptCount(1).maxAttempts(3)
                .leaseOwner("worker-a").leaseUntil(LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC))
                .fencingToken(1L).rowVersion(1L)
                .checkpoint("{\"stage\":\"received\",\"processedChunks\":0,\"totalChunks\":0,"
                        + "\"embeddingBatchIndex\":0,\"vectorUpsertIndex\":0}").build();
    }

    private RagIngestJobEntity terminalJob(RagIngestJobStatus status) {
        RagIngestCheckpoint checkpoint = status == RagIngestJobStatus.COMPLETED
                ? new RagIngestCheckpoint(RagIngestStage.COMPLETED, 3, 3, 1, 1)
                : RagIngestCheckpoint.initial();
        return new RagIngestJobEntity("tenant-a", "kb-1", "doc-1", "version-1", "job-1",
                "task-key-1", RagIngestOperation.INGEST, 2L, status, checkpoint, 1, 3,
                null, null, 11L, 8L, null,
                status == RagIngestJobStatus.FAILED ? "RAG_FAILED" : null,
                status == RagIngestJobStatus.FAILED ? "failed" : null);
    }
}
