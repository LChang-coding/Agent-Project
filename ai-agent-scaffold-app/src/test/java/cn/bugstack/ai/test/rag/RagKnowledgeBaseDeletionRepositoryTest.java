package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.infrastructure.adapter.repository.RagKnowledgeBaseDeletionRepositoryImpl;
import cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagChunkDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDeleteTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBasePO;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceCodec;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;

/** 知识库删除屏障、任务账本与绑定停用的短事务编排。 */
public class RagKnowledgeBaseDeletionRepositoryTest {

    private IRagKnowledgeBaseDeleteTaskDao taskDao;
    private IRagKnowledgeBaseDao knowledgeBaseDao;
    private IRagDocumentDao documentDao;
    private IRagDocumentVersionDao documentVersionDao;
    private IRagChunkDao chunkDao;
    private IRagIngestTaskDao ingestTaskDao;
    private IRagAgentBindingDao bindingDao;
    private RagKnowledgeBaseDeletionRepositoryImpl repository;

    @Before
    public void setUp() {
        taskDao = Mockito.mock(IRagKnowledgeBaseDeleteTaskDao.class);
        knowledgeBaseDao = Mockito.mock(IRagKnowledgeBaseDao.class);
        documentDao = Mockito.mock(IRagDocumentDao.class);
        documentVersionDao = Mockito.mock(IRagDocumentVersionDao.class);
        chunkDao = Mockito.mock(IRagChunkDao.class);
        ingestTaskDao = Mockito.mock(IRagIngestTaskDao.class);
        bindingDao = Mockito.mock(IRagAgentBindingDao.class);
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new RagKnowledgeBaseDeletionRepositoryImpl(taskDao, knowledgeBaseDao,
                documentDao, documentVersionDao, chunkDao, ingestTaskDao, bindingDao,
                new RagPersistenceMapper(new RagPersistenceCodec(objectMapper)), objectMapper);
        Mockito.when(knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate("tenant-a", "kb-a"))
                .thenReturn(knowledgeBasePo());
        Mockito.when(documentDao.countByTenantAndKnowledgeBaseId("tenant-a", "kb-a")).thenReturn(2);
        Mockito.when(taskDao.insert(any())).thenReturn(1);
        Mockito.when(knowledgeBaseDao.updateByTenantAndRevision(
                Mockito.eq("tenant-a"), any(), Mockito.eq(7L))).thenReturn(1);
    }

    @Test
    public void shouldAtomicallyCreateBarrierTaskAndDisableBindings() {
        Assert.assertTrue(repository.register("tenant-a", registration(2)));

        Mockito.verify(taskDao).insert(Mockito.argThat(task ->
                "task-a".equals(task.getTaskId()) && task.getCheckpoint().contains("totalDocuments\":2")));
        Mockito.verify(knowledgeBaseDao).updateByTenantAndRevision(
                Mockito.eq("tenant-a"), Mockito.argThat(kb -> "deleting".equals(kb.getStatus())),
                Mockito.eq(7L));
        Mockito.verify(bindingDao).disableByTenantAndKnowledgeBaseId("tenant-a", "kb-a");
    }

    @Test
    public void shouldRejectActiveIngestBeforeAnyMutation() {
        Mockito.when(ingestTaskDao.countActiveByTenantAndKnowledgeBaseId("tenant-a", "kb-a"))
                .thenReturn(1);

        AppException error = Assert.assertThrows(AppException.class,
                () -> repository.register("tenant-a", registration(2)));

        Assert.assertEquals("RAG_KNOWLEDGE_BASE_TASKS_ACTIVE", error.getCode());
        Mockito.verify(taskDao, Mockito.never()).insert(any());
        Mockito.verify(knowledgeBaseDao, Mockito.never()).updateByTenantAndRevision(
                Mockito.anyString(), any(), Mockito.anyLong());
        Mockito.verifyNoInteractions(bindingDao);
    }

    @Test
    public void shouldRejectChangedDocumentSetBeforeAnyMutation() {
        Mockito.when(documentDao.countByTenantAndKnowledgeBaseId("tenant-a", "kb-a")).thenReturn(3);

        AppException error = Assert.assertThrows(AppException.class,
                () -> repository.register("tenant-a", registration(2)));

        Assert.assertEquals("RAG_KNOWLEDGE_BASE_DOCUMENT_SET_CHANGED", error.getCode());
        Mockito.verify(taskDao, Mockito.never()).insert(any());
        Mockito.verifyNoInteractions(bindingDao);
    }

    @Test
    public void shouldReturnExistingRegistrationWithoutMutatingAggregate() {
        Mockito.when(taskDao.queryByTenantAndKnowledgeBaseId("tenant-a", "kb-a"))
                .thenReturn(new cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteTaskPO());

        Assert.assertFalse(repository.register("tenant-a", registration(2)));

        Mockito.verifyNoInteractions(documentDao, ingestTaskDao, bindingDao);
        Mockito.verify(taskDao, Mockito.never()).insert(any());
        Mockito.verify(knowledgeBaseDao, Mockito.never()).updateByTenantAndRevision(
                Mockito.anyString(), any(), Mockito.anyLong());
    }

    @Test
    public void shouldScanLightweightCandidateAndReturnClaimedSnapshot() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Mockito.when(taskDao.queryDueCandidates(LocalDateTime.ofInstant(now, ZoneOffset.UTC), 20))
                .thenReturn(List.of(new cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteCandidatePO(
                        "tenant-a", "task-a")));
        Mockito.when(taskDao.claimDue(Mockito.eq("tenant-a"), Mockito.eq("task-a"),
                Mockito.eq("worker-a"), Mockito.any(), Mockito.any())).thenReturn(1);
        Mockito.when(taskDao.queryByTenantAndTaskId("tenant-a", "task-a"))
                .thenReturn(runningTaskPo(now.plusSeconds(30)));

        Assert.assertEquals("task-a", repository.listDueCandidates(now, 20).get(0).taskId());
        var claimed = repository.claim("tenant-a", "task-a", "worker-a",
                now, now.plusSeconds(30)).orElseThrow();

        Assert.assertEquals("worker-a", claimed.lease().owner());
        Assert.assertEquals(1L, claimed.fencingToken());
    }

    @Test
    public void shouldNotReadSnapshotWhenClaimLosesRace() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Mockito.when(taskDao.claimDue(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.any(), Mockito.any())).thenReturn(0);

        Assert.assertTrue(repository.claim("tenant-a", "task-a", "worker-b",
                now, now.plusSeconds(30)).isEmpty());

        Mockito.verify(taskDao, Mockito.never()).queryByTenantAndTaskId(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void shouldCompleteKnowledgeBaseAndTaskOnlyAfterZeroResidualVerification() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        var task = verifyingTaskPo(now.plusSeconds(30));
        Mockito.when(taskDao.queryByTenantAndTaskId("tenant-a", "task-a")).thenReturn(task);
        Mockito.when(taskDao.queryByTenantAndTaskIdForUpdate("tenant-a", "task-a")).thenReturn(task);
        RagKnowledgeBasePO deleting = knowledgeBasePo();
        deleting.setStatus("deleting");
        deleting.setRevision(8L);
        Mockito.when(knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate("tenant-a", "kb-a"))
                .thenReturn(deleting);
        Mockito.when(documentDao.countByTenantAndKnowledgeBaseId("tenant-a", "kb-a")).thenReturn(2);
        Mockito.when(knowledgeBaseDao.updateByTenantAndRevision(
                Mockito.eq("tenant-a"), Mockito.any(), Mockito.eq(8L))).thenReturn(1);
        Mockito.when(taskDao.updateClaimedByTenantFenceAndRevision(
                Mockito.eq("tenant-a"), Mockito.any(), Mockito.eq(4L),
                Mockito.eq("worker-a"), Mockito.eq(1L), Mockito.any())).thenReturn(1);

        repository.completeClaimed("tenant-a", "task-a", 4L, "worker-a", 1L, now);

        Mockito.verify(knowledgeBaseDao).updateByTenantAndRevision(Mockito.eq("tenant-a"),
                Mockito.argThat(value -> "deleted".equals(value.getStatus())), Mockito.eq(8L));
        Mockito.verify(taskDao).updateClaimedByTenantFenceAndRevision(Mockito.eq("tenant-a"),
                Mockito.argThat(value -> "completed".equals(value.getStatus())), Mockito.eq(4L),
                Mockito.eq("worker-a"), Mockito.eq(1L), Mockito.any());
    }

    @Test
    public void shouldRejectFinalizationWhenAnyDocumentResidualExists() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        var task = verifyingTaskPo(now.plusSeconds(30));
        Mockito.when(taskDao.queryByTenantAndTaskId("tenant-a", "task-a")).thenReturn(task);
        Mockito.when(taskDao.queryByTenantAndTaskIdForUpdate("tenant-a", "task-a")).thenReturn(task);
        RagKnowledgeBasePO deleting = knowledgeBasePo();
        deleting.setStatus("deleting");
        deleting.setRevision(8L);
        Mockito.when(knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate("tenant-a", "kb-a"))
                .thenReturn(deleting);
        Mockito.when(documentDao.countByTenantAndKnowledgeBaseId("tenant-a", "kb-a")).thenReturn(2);
        Mockito.when(documentDao.countNotDeletedByTenantAndKnowledgeBaseId("tenant-a", "kb-a"))
                .thenReturn(1);

        AppException error = Assert.assertThrows(AppException.class,
                () -> repository.completeClaimed(
                        "tenant-a", "task-a", 4L, "worker-a", 1L, now));

        Assert.assertEquals("RAG_KB_DELETE_RESIDUALS", error.getCode());
        Mockito.verify(knowledgeBaseDao, Mockito.never()).updateByTenantAndRevision(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong());
        Mockito.verify(taskDao, Mockito.never()).updateClaimedByTenantFenceAndRevision(
                Mockito.anyString(), Mockito.any(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.any());
    }

    private RagKnowledgeBaseDeleteRegistration registration(int documentCount) {
        RagKnowledgeBaseEntity deleting = knowledgeBase().requestDeletion();
        RagKnowledgeBaseDeleteTaskEntity task = RagKnowledgeBaseDeleteTaskEntity.pending(
                "tenant-a", "kb-a", "owner-a", "task-a", "a".repeat(64), documentCount, 5);
        return new RagKnowledgeBaseDeleteRegistration(deleting, 7L, task);
    }

    private RagKnowledgeBaseEntity knowledgeBase() {
        return new RagKnowledgeBaseEntity("tenant-a", "owner-a", "kb-a", "企业库", null,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, "profile-a", 768,
                "rag-kb-a", 1L, 7L);
    }

    private RagKnowledgeBasePO knowledgeBasePo() {
        return RagKnowledgeBasePO.builder().tenantId("tenant-a").ownerUserId("owner-a")
                .knowledgeBaseId("kb-a").knowledgeBaseName("企业库").visibility("tenant")
                .status("active").retrievalProfileId("profile-a").embeddingDimension(768)
                .collectionAlias("rag-kb-a").currentGeneration(1L).revision(7L).build();
    }

    private cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteTaskPO runningTaskPo(
            Instant leaseUntil) {
        return cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteTaskPO.builder()
                .tenantId("tenant-a").knowledgeBaseId("kb-a").taskId("task-a")
                .requestedByUserId("owner-a").taskKey("a".repeat(64)).status("running")
                .checkpoint("{\"stage\":\"received\",\"totalDocuments\":2,"
                        + "\"completedDocuments\":0,\"currentDocumentId\":null}")
                .attemptCount(1).maxAttempts(5).leaseOwner("worker-a")
                .leaseUntil(LocalDateTime.ofInstant(leaseUntil, ZoneOffset.UTC))
                .fencingToken(1L).rowVersion(1L).build();
    }

    private cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBaseDeleteTaskPO verifyingTaskPo(
            Instant leaseUntil) {
        var task = runningTaskPo(leaseUntil);
        task.setCheckpoint("{\"stage\":\"verifying\",\"totalDocuments\":2,"
                + "\"completedDocuments\":2,\"currentDocumentId\":null}");
        task.setRowVersion(4L);
        return task;
    }
}
