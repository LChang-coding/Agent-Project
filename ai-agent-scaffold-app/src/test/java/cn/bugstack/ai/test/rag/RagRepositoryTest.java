package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
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

/**
 * RAG Repository 租户参数和任务原子领取编排测试。
 */
public class RagRepositoryTest {

    private IRagKnowledgeBaseDao knowledgeBaseDao;
    private IRagIngestTaskDao ingestTaskDao;
    private RagRepository repository;

    @Before
    public void setUp() {
        knowledgeBaseDao = Mockito.mock(IRagKnowledgeBaseDao.class);
        ingestTaskDao = Mockito.mock(IRagIngestTaskDao.class);
        RagPersistenceCodec codec = new RagPersistenceCodec(new ObjectMapper());
        repository = new RagRepository(knowledgeBaseDao, Mockito.mock(IRagDocumentDao.class),
                Mockito.mock(IRagDocumentVersionDao.class), ingestTaskDao, Mockito.mock(IRagChunkDao.class),
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
}
