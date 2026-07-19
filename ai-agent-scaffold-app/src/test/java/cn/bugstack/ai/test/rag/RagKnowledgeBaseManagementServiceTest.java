package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseManagementService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识库管理领域服务测试。 */
public class RagKnowledgeBaseManagementServiceTest {

    @Test
    public void shouldCreateTenantKnowledgeBaseWithServerDefaultsForAdminAndOwner() {
        IRagRepository repository = mock(IRagRepository.class);
        when(repository.listKnowledgeBases("tenant-sensitive-42")).thenReturn(List.of());
        when(repository.insertKnowledgeBase(any(), any())).thenReturn(1);
        RagKnowledgeBaseManagementService service = service(repository);

        RagKnowledgeBaseEntity created = service.create(
                "tenant-sensitive-42", "admin-1", "admin", "  企业手册  ", "  内部文档  ");
        service.create("tenant-sensitive-42", "owner-1", "OWNER", "产品文档", null);

        Assert.assertEquals("企业手册", created.name());
        Assert.assertEquals("内部文档", created.description());
        Assert.assertEquals(RagVisibility.TENANT, created.visibility());
        Assert.assertEquals(RagKnowledgeBaseStatus.ACTIVE, created.status());
        Assert.assertEquals(768, created.embeddingDimension());
        Assert.assertEquals(0L, created.currentGeneration());
        Assert.assertEquals(0L, created.revision());
        Assert.assertTrue(created.knowledgeBaseId().startsWith("kb_"));
        Assert.assertFalse(created.collectionAlias().contains("tenant-sensitive-42"));

        ArgumentCaptor<RagKnowledgeBaseEntity> captor = ArgumentCaptor.forClass(RagKnowledgeBaseEntity.class);
        verify(repository, times(2)).insertKnowledgeBase(
                org.mockito.ArgumentMatchers.eq("tenant-sensitive-42"), captor.capture());
        Assert.assertEquals("admin-1", captor.getAllValues().get(0).ownerUserId());
        Assert.assertEquals("owner-1", captor.getAllValues().get(1).ownerUserId());
    }

    @Test
    public void shouldRejectOrdinaryUserAndMissingTrustedContext() {
        IRagRepository repository = mock(IRagRepository.class);
        RagKnowledgeBaseManagementService service = service(repository);

        assertAppException("RAG_ADMIN_REQUIRED",
                () -> service.create("tenant-a", "member-1", "member", "手册", null));
        assertAppException("RAG_AUTH_CONTEXT_MISSING", () -> service.list(null, null));
        verify(repository, never()).insertKnowledgeBase(any(), any());
    }

    @Test
    public void shouldReturnStableConflictForDuplicateNameAndConcurrentInsert() {
        IRagRepository duplicateRepository = mock(IRagRepository.class);
        when(duplicateRepository.listKnowledgeBases("tenant-a")).thenReturn(List.of(knowledgeBase("tenant-a", "企业手册")));
        assertAppException("RAG_KNOWLEDGE_BASE_CONFLICT",
                () -> service(duplicateRepository).create("tenant-a", "admin-1", "admin", " 企业手册 ", null));

        IRagRepository concurrentRepository = mock(IRagRepository.class);
        when(concurrentRepository.listKnowledgeBases("tenant-a")).thenReturn(List.of());
        when(concurrentRepository.insertKnowledgeBase(any(), any()))
                .thenThrow(new AppException("RAG_KNOWLEDGE_BASE_CONFLICT", "并发冲突"));
        assertAppException("RAG_KNOWLEDGE_BASE_CONFLICT",
                () -> service(concurrentRepository).create("tenant-a", "admin-1", "admin", "企业手册", null));
    }

    @Test
    public void shouldUpdateOnlyMutableFieldsWithRevisionCas() {
        IRagRepository repository = mock(IRagRepository.class);
        RagKnowledgeBaseEntity current = knowledgeBase("tenant-a", "旧名称", RagKnowledgeBaseStatus.ACTIVE, 4);
        when(repository.findKnowledgeBase("tenant-a", "kb-1")).thenReturn(Optional.of(current));
        when(repository.listKnowledgeBases("tenant-a")).thenReturn(List.of(current));
        when(repository.updateKnowledgeBase(org.mockito.ArgumentMatchers.eq("tenant-a"), any(),
                org.mockito.ArgumentMatchers.eq(4L))).thenReturn(1);

        RagKnowledgeBaseEntity updated = service(repository).update(
                "tenant-a", "admin-1", "admin", "kb-1", 4, " 新名称 ", " 新描述 ");

        Assert.assertEquals("新名称", updated.name());
        Assert.assertEquals("新描述", updated.description());
        Assert.assertEquals(5L, updated.revision());
        Assert.assertEquals(current.collectionAlias(), updated.collectionAlias());
        Assert.assertEquals(current.currentGeneration(), updated.currentGeneration());
    }

    @Test
    public void shouldRejectStaleDuplicateAndDeletingKnowledgeBaseUpdate() {
        IRagRepository staleRepository = mock(IRagRepository.class);
        when(staleRepository.findKnowledgeBase("tenant-a", "kb-1"))
                .thenReturn(Optional.of(knowledgeBase("tenant-a", "旧名称", RagKnowledgeBaseStatus.ACTIVE, 4)));
        assertAppException("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", () -> service(staleRepository).update(
                "tenant-a", "admin-1", "admin", "kb-1", 3, "新名称", null));

        IRagRepository duplicateRepository = mock(IRagRepository.class);
        RagKnowledgeBaseEntity current = knowledgeBase("tenant-a", "旧名称", RagKnowledgeBaseStatus.ACTIVE, 4);
        when(duplicateRepository.findKnowledgeBase("tenant-a", "kb-1")).thenReturn(Optional.of(current));
        when(duplicateRepository.listKnowledgeBases("tenant-a")).thenReturn(List.of(current,
                new RagKnowledgeBaseEntity("tenant-a", "owner-1", "kb-2", "已存在", null,
                        RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null, 768,
                        "rag_hash_kb-2", 0, 0)));
        assertAppException("RAG_KNOWLEDGE_BASE_CONFLICT", () -> service(duplicateRepository).update(
                "tenant-a", "admin-1", "admin", "kb-1", 4, " 已存在 ", null));

        IRagRepository deletingRepository = mock(IRagRepository.class);
        when(deletingRepository.findKnowledgeBase("tenant-a", "kb-1"))
                .thenReturn(Optional.of(knowledgeBase("tenant-a", "旧名称", RagKnowledgeBaseStatus.DELETING, 4)));
        assertAppException("RAG_KNOWLEDGE_BASE_UNAVAILABLE", () -> service(deletingRepository).update(
                "tenant-a", "admin-1", "admin", "kb-1", 4, "新名称", null));

        IRagRepository concurrentRepository = mock(IRagRepository.class);
        when(concurrentRepository.findKnowledgeBase("tenant-a", "kb-1")).thenReturn(Optional.of(current));
        when(concurrentRepository.listKnowledgeBases("tenant-a")).thenReturn(List.of(current));
        when(concurrentRepository.updateKnowledgeBase(org.mockito.ArgumentMatchers.eq("tenant-a"), any(),
                org.mockito.ArgumentMatchers.eq(4L))).thenReturn(0);
        assertAppException("RAG_KNOWLEDGE_BASE_REVISION_CONFLICT", () -> service(concurrentRepository).update(
                "tenant-a", "admin-1", "admin", "kb-1", 4, "新名称", null));

        IRagRepository memberRepository = mock(IRagRepository.class);
        assertAppException("RAG_ADMIN_REQUIRED", () -> service(memberRepository).update(
                "tenant-a", "member-1", "member", "kb-1", 4, "新名称", null));
        verify(memberRepository, never()).findKnowledgeBase(any(), any());
    }

    private RagKnowledgeBaseManagementService service(IRagRepository repository) {
        return new RagKnowledgeBaseManagementService(repository, new RagKnowledgeBaseAuthorizationService());
    }

    private RagKnowledgeBaseEntity knowledgeBase(String tenantId, String name) {
        return knowledgeBase(tenantId, name, RagKnowledgeBaseStatus.ACTIVE, 0);
    }

    private RagKnowledgeBaseEntity knowledgeBase(String tenantId, String name,
                                                  RagKnowledgeBaseStatus status, long revision) {
        return new RagKnowledgeBaseEntity(tenantId, "owner-1", "kb-1", name, null,
                RagVisibility.TENANT, status, null, 768,
                "rag_hash_kb-1", 0L, revision);
    }

    private void assertAppException(String code, Runnable action) {
        try {
            action.run();
            Assert.fail("预期抛出领域异常：" + code);
        } catch (AppException e) {
            Assert.assertEquals(code, e.getCode());
        }
    }
}
