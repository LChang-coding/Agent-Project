package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.domain.rag.service.RagRetrievalConfigurationService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 检索策略和运行目标绑定的管理员、租户与 CAS 测试。 */
public class RagRetrievalConfigurationServiceTest {

    private IRagRepository repository;
    private RagRetrievalConfigurationService service;

    @Before
    public void setUp() {
        repository = Mockito.mock(IRagRepository.class);
        service = new RagRetrievalConfigurationService(repository, new RagKnowledgeBaseAuthorizationService());
    }

    @Test
    public void shouldCreateValidatedHybridProfileForTrustedAdmin() {
        when(repository.insertRetrievalProfile(eq("tenant-a"), any())).thenReturn(1);

        RagRetrievalProfileEntity value = service.createProfile("tenant-a", "admin-a", "admin", profileValues());

        Assert.assertTrue(value.profileId().startsWith("profile_"));
        Assert.assertEquals(RagRetrievalMode.HYBRID, value.mode());
        Assert.assertEquals(0, value.revision());
        verify(repository).insertRetrievalProfile("tenant-a", value);
    }

    @Test
    public void shouldRejectProfileMutationForOrdinaryMember() {
        AppException error = Assert.assertThrows(AppException.class,
                () -> service.createProfile("tenant-a", "member-a", "member", profileValues()));

        Assert.assertEquals("RAG_ADMIN_REQUIRED", error.getCode());
        verify(repository, never()).insertRetrievalProfile(any(), any());
    }

    @Test
    public void shouldRejectStaleProfileRevisionBeforeUpdate() {
        when(repository.findRetrievalProfile("tenant-a", "profile-a")).thenReturn(Optional.of(profile(4)));

        AppException error = Assert.assertThrows(AppException.class, () -> service.updateProfile(
                "tenant-a", "owner-a", "owner", "profile-a", 3, profileValues()));

        Assert.assertEquals("RAG_PROFILE_REVISION_CONFLICT", error.getCode());
        verify(repository, never()).updateRetrievalProfile(any(), any(), Mockito.anyLong());
    }

    @Test
    public void shouldCreateBindingOnlyWithinSameTenantAndProfileBudget() {
        when(repository.findKnowledgeBase("tenant-a", "kb-a")).thenReturn(Optional.of(knowledgeBase("tenant-a")));
        when(repository.findRetrievalProfile("tenant-a", "profile-a")).thenReturn(Optional.of(profile(2)));
        when(repository.insertBinding(eq("tenant-a"), any())).thenReturn(1);

        RagAgentBindingEntity binding = service.createBinding("tenant-a", "owner-a", "owner",
                new RagRetrievalConfigurationService.BindingValues(RagBindingTargetType.AGENT, "agent-a",
                        "kb-a", "profile-a", true, 512, 10));

        Assert.assertEquals("tenant-a", binding.tenantId());
        Assert.assertTrue(binding.bindingId().startsWith("binding_"));
        Assert.assertEquals(512, binding.maxTokens());
    }

    @Test
    public void shouldRejectCrossTenantKnowledgeBaseReturnedByRepository() {
        when(repository.findKnowledgeBase("tenant-a", "kb-a")).thenReturn(Optional.of(knowledgeBase("tenant-b")));

        AppException error = Assert.assertThrows(AppException.class, () -> service.createBinding(
                "tenant-a", "owner-a", "owner", new RagRetrievalConfigurationService.BindingValues(
                        RagBindingTargetType.AGENT, "agent-a", "kb-a", "profile-a", false, 512, 0)));

        Assert.assertEquals("RAG_KNOWLEDGE_BASE_NOT_FOUND", error.getCode());
        verify(repository, never()).insertBinding(any(), any());
    }

    @Test
    public void shouldDeleteBindingWithRevisionCas() {
        RagAgentBindingEntity binding = new RagAgentBindingEntity("tenant-a", "binding-a",
                RagBindingTargetType.AGENT, "agent-a", "kb-a", "profile-a", false, 512, 0, 3);
        when(repository.findBinding("tenant-a", "binding-a")).thenReturn(Optional.of(binding));
        when(repository.deleteBinding("tenant-a", "binding-a", 3)).thenReturn(1);

        service.deleteBinding("tenant-a", "admin-a", "admin", "binding-a", 3);

        verify(repository).deleteBinding("tenant-a", "binding-a", 3);
    }

    private RagRetrievalConfigurationService.ProfileValues profileValues() {
        return new RagRetrievalConfigurationService.ProfileValues("高质量混合检索", RagRetrievalMode.HYBRID,
                RagFusionStrategy.RRF, BigDecimal.ONE, BigDecimal.ONE, 20, 20, 20,
                true, 10, 5, 1, 1024, null, false, true);
    }

    private RagRetrievalProfileEntity profile(long revision) {
        RagRetrievalConfigurationService.ProfileValues value = profileValues();
        return new RagRetrievalProfileEntity("tenant-a", "profile-a", value.name(), value.mode(),
                value.fusionStrategy(), value.denseWeight(), value.sparseWeight(), value.denseTopK(),
                value.sparseTopK(), value.fusionTopK(), value.rerankEnabled(), value.rerankTopK(),
                value.finalTopK(), value.neighborWindow(), value.maxContextTokens(), value.scoreThreshold(),
                value.queryRewriteEnabled(), value.deduplicateEnabled(), revision);
    }

    private RagKnowledgeBaseEntity knowledgeBase(String tenantId) {
        return new RagKnowledgeBaseEntity(tenantId, "owner-a", "kb-a", "企业知识库", null,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null, 768, "alias-a", 3, 1);
    }
}
