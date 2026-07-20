package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.infrastructure.adapter.repository.RagKnowledgeBaseDeletionRepositoryImpl;
import cn.bugstack.ai.infrastructure.dao.IRagAgentBindingDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
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

import static org.mockito.ArgumentMatchers.any;

/** 知识库删除屏障、任务账本与绑定停用的短事务编排。 */
public class RagKnowledgeBaseDeletionRepositoryTest {

    private IRagKnowledgeBaseDeleteTaskDao taskDao;
    private IRagKnowledgeBaseDao knowledgeBaseDao;
    private IRagDocumentDao documentDao;
    private IRagIngestTaskDao ingestTaskDao;
    private IRagAgentBindingDao bindingDao;
    private RagKnowledgeBaseDeletionRepositoryImpl repository;

    @Before
    public void setUp() {
        taskDao = Mockito.mock(IRagKnowledgeBaseDeleteTaskDao.class);
        knowledgeBaseDao = Mockito.mock(IRagKnowledgeBaseDao.class);
        documentDao = Mockito.mock(IRagDocumentDao.class);
        ingestTaskDao = Mockito.mock(IRagIngestTaskDao.class);
        bindingDao = Mockito.mock(IRagAgentBindingDao.class);
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new RagKnowledgeBaseDeletionRepositoryImpl(taskDao, knowledgeBaseDao,
                documentDao, ingestTaskDao, bindingDao,
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

    private RagKnowledgeBaseDeleteRegistration registration(int documentCount) {
        RagKnowledgeBaseEntity deleting = knowledgeBase().requestDeletion();
        RagKnowledgeBaseDeleteTaskEntity task = RagKnowledgeBaseDeleteTaskEntity.pending(
                "tenant-a", "kb-a", "task-a", "a".repeat(64), documentCount, 5);
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
}
