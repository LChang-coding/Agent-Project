package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagDocumentDeletionRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.infrastructure.adapter.repository.RagDocumentDeletionRegistrationRepository;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentDao;
import cn.bugstack.ai.infrastructure.dao.IRagDocumentVersionDao;
import cn.bugstack.ai.infrastructure.dao.IRagIngestTaskDao;
import cn.bugstack.ai.infrastructure.dao.IRagKnowledgeBaseDao;
import cn.bugstack.ai.infrastructure.dao.IRagOutboxDao;
import cn.bugstack.ai.infrastructure.dao.po.RagDocumentPO;
import cn.bugstack.ai.infrastructure.dao.po.RagIngestTaskPO;
import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBasePO;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceCodec;
import cn.bugstack.ai.infrastructure.rag.persistence.RagPersistenceMapper;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 删除登记Repository的聚合锁、活动任务互斥和原子写入编排测试。 */
public class RagDocumentDeletionRegistrationRepositoryTest {

    @Test
    public void shouldLockAggregateThenWriteTaskTombstonesVersionsAndOutbox() {
        Fixture fixture = fixture(false);

        Assert.assertTrue(fixture.repository.register("tenant-a", registration()));

        verify(fixture.documentDao).queryByTenantKnowledgeBaseAndDocumentIdForUpdate(
                "tenant-a", "kb-a", "doc-a");
        verify(fixture.ingestTaskDao).queryActiveByTenantAndDocumentId("tenant-a", "doc-a");
        verify(fixture.ingestTaskDao).insert(any());
        verify(fixture.documentDao).updateByTenantAndRevision(anyString(), any(), org.mockito.Mockito.eq(7L));
        verify(fixture.versionDao, org.mockito.Mockito.times(2))
                .updateByTenantAndRevision(anyString(), any(), anyLong());
        verify(fixture.outboxDao).insert(org.mockito.ArgumentMatchers.argThat(outbox ->
                "task-delete".equals(outbox.getTaskId())
                        && "rag.ingest.requested.v1".equals(outbox.getEventType())
                        && outbox.getPayload().contains("tenant-a")));
    }

    @Test
    public void shouldRejectActiveTaskBeforeAnyMutation() {
        Fixture fixture = fixture(true);

        AppException error = Assert.assertThrows(AppException.class,
                () -> fixture.repository.register("tenant-a", registration()));

        Assert.assertEquals("RAG_DOCUMENT_TASK_CONFLICT", error.getCode());
        verify(fixture.ingestTaskDao, never()).insert(any());
        verify(fixture.documentDao, never()).updateByTenantAndRevision(anyString(), any(), anyLong());
        verify(fixture.versionDao, never()).updateByTenantAndRevision(anyString(), any(), anyLong());
        verify(fixture.outboxDao, never()).insert(any());
    }

    private Fixture fixture(boolean activeTask) {
        IRagIngestTaskDao taskDao = mock(IRagIngestTaskDao.class);
        IRagKnowledgeBaseDao knowledgeBaseDao = mock(IRagKnowledgeBaseDao.class);
        IRagDocumentDao documentDao = mock(IRagDocumentDao.class);
        IRagDocumentVersionDao versionDao = mock(IRagDocumentVersionDao.class);
        IRagOutboxDao outboxDao = mock(IRagOutboxDao.class);
        when(knowledgeBaseDao.queryByTenantAndKnowledgeBaseIdForUpdate("tenant-a", "kb-a"))
                .thenReturn(RagKnowledgeBasePO.builder().tenantId("tenant-a")
                        .knowledgeBaseId("kb-a").status("active").revision(3L).build());
        when(documentDao.queryByTenantKnowledgeBaseAndDocumentIdForUpdate("tenant-a", "kb-a", "doc-a"))
                .thenReturn(RagDocumentPO.builder().tenantId("tenant-a").knowledgeBaseId("kb-a")
                        .documentId("doc-a").revision(7L).status("ready").build());
        if (activeTask) when(taskDao.queryActiveByTenantAndDocumentId("tenant-a", "doc-a"))
                .thenReturn(new RagIngestTaskPO());
        when(taskDao.insert(any())).thenReturn(1);
        when(documentDao.updateByTenantAndRevision(anyString(), any(), anyLong())).thenReturn(1);
        when(versionDao.updateByTenantAndRevision(anyString(), any(), anyLong())).thenReturn(1);
        when(outboxDao.insert(any())).thenReturn(1);
        ObjectMapper objectMapper = new ObjectMapper();
        RagPersistenceMapper mapper = new RagPersistenceMapper(new RagPersistenceCodec(objectMapper));
        return new Fixture(taskDao, knowledgeBaseDao, documentDao, versionDao, outboxDao,
                new RagDocumentDeletionRegistrationRepository(taskDao, knowledgeBaseDao,
                        documentDao, versionDao, outboxDao,
                        mapper, new RagProperties(), objectMapper));
    }

    private RagDocumentDeletionRegistration registration() {
        RagDocumentEntity document = new RagDocumentEntity("tenant-a", "owner-a", RagVisibility.TENANT,
                "kb-a", "doc-a", "document.md", "ver-2", 3L, null,
                RagDocumentStatus.DELETING, 8L);
        List<RagDocumentVersionEntity> versions = List.of(
                version("ver-1", 1, 5L), version("ver-2", 2, 6L));
        RagIngestJobEntity task = RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-a", "ver-2",
                "task-delete", "delete-key", RagIngestOperation.DELETE, 3L, 3);
        return new RagDocumentDeletionRegistration(document, versions, task, "event-delete");
    }

    private RagDocumentVersionEntity version(String id, int number, long revision) {
        return new RagDocumentVersionEntity("tenant-a", "kb-a", "doc-a", id, number, 3L,
                "rag", "source/" + id, null, null, id + ".md", "a".repeat(64), "text/markdown",
                10L, RagDocumentVersionStatus.DELETING, null, null, null, revision);
    }

    private record Fixture(IRagIngestTaskDao ingestTaskDao,
                           IRagKnowledgeBaseDao knowledgeBaseDao,
                           IRagDocumentDao documentDao,
                           IRagDocumentVersionDao versionDao,
                           IRagOutboxDao outboxDao,
                           RagDocumentDeletionRegistrationRepository repository) {
    }
}
