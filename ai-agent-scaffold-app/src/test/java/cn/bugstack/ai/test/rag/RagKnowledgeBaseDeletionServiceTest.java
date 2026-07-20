package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagKnowledgeBaseDeletionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteRegistration;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseDeletionService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

/** 知识库级联删除受理的权限、revision、审计人和幂等测试。 */
public class RagKnowledgeBaseDeletionServiceTest {
    private IRagRepository repository;
    private RagKnowledgeBaseDeletionRepository deletionRepository;
    private RagKnowledgeBaseDeletionService service;

    @Before
    public void setUp() {
        repository = Mockito.mock(IRagRepository.class);
        deletionRepository = Mockito.mock(RagKnowledgeBaseDeletionRepository.class);
        service = new RagKnowledgeBaseDeletionService(repository, deletionRepository,
                new RagKnowledgeBaseAuthorizationService());
        Mockito.when(repository.findKnowledgeBase("tenant-a", "kb-a"))
                .thenReturn(Optional.of(knowledgeBase()));
        Mockito.when(repository.listDocuments("tenant-a", "kb-a")).thenReturn(List.of());
    }

    @Test
    public void shouldRegisterDeletingBarrierWithRequesterAudit() {
        Mockito.when(deletionRepository.register(Mockito.eq("tenant-a"), Mockito.any())).thenReturn(true);

        RagKnowledgeBaseDeleteTaskEntity result = service.requestDeletion(
                "tenant-a", "owner-a", "owner", "kb-a", 7L);

        Assert.assertEquals("owner-a", result.requestedByUserId());
        ArgumentCaptor<RagKnowledgeBaseDeleteRegistration> captor =
                ArgumentCaptor.forClass(RagKnowledgeBaseDeleteRegistration.class);
        Mockito.verify(deletionRepository).register(Mockito.eq("tenant-a"), captor.capture());
        Assert.assertEquals(RagKnowledgeBaseStatus.DELETING, captor.getValue().knowledgeBase().status());
        Assert.assertEquals(0, captor.getValue().task().checkpoint().totalDocuments());
    }

    @Test
    public void shouldReturnExistingTaskWithoutRegisteringAgain() {
        RagKnowledgeBaseDeleteTaskEntity existing = RagKnowledgeBaseDeleteTaskEntity.pending(
                "tenant-a", "kb-a", "owner-a", "task-existing", "a".repeat(64), 0, 5);
        Mockito.when(deletionRepository.findByKnowledgeBaseId("tenant-a", "kb-a"))
                .thenReturn(Optional.of(existing));

        Assert.assertSame(existing, service.requestDeletion(
                "tenant-a", "owner-a", "owner", "kb-a", 0L));
        Mockito.verify(deletionRepository, Mockito.never()).register(Mockito.anyString(), Mockito.any());
    }

    @Test
    public void shouldRejectMemberAndStaleRevisionBeforeRegistration() {
        AppException forbidden = Assert.assertThrows(AppException.class, () -> service.requestDeletion(
                "tenant-a", "member-a", "member", "kb-a", 7L));
        Assert.assertEquals("RAG_ADMIN_REQUIRED", forbidden.getCode());

        AppException stale = Assert.assertThrows(AppException.class, () -> service.requestDeletion(
                "tenant-a", "owner-a", "owner", "kb-a", 6L));
        Assert.assertEquals("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", stale.getCode());
        Mockito.verify(deletionRepository, Mockito.never()).register(Mockito.anyString(), Mockito.any());
    }

    private RagKnowledgeBaseEntity knowledgeBase() {
        return new RagKnowledgeBaseEntity("tenant-a", "owner-a", "kb-a", "企业库", null,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, "profile-a", 768,
                "rag-kb-a", 1L, 7L);
    }
}
