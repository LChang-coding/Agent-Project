package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseResponseDTO;
import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseUpdateRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseManagementService;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.trigger.http.RagKnowledgeBaseController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识库管理接口可信租户边界测试。 */
public class RagKnowledgeBaseControllerTest {

    @After
    public void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    public void shouldListOnlyWithTrustedTenantScopeForOrdinaryMember() {
        IRagRepository repository = mock(IRagRepository.class);
        RagKnowledgeBaseManagementService service = new RagKnowledgeBaseManagementService(
                repository, new RagKnowledgeBaseAuthorizationService());
        RagKnowledgeBaseController controller = new RagKnowledgeBaseController(service);
        TenantContextHolder.set(TenantContext.builder()
                .tenantId("tenant-a").userId("member-1").roleCode("member").build());
        when(repository.listKnowledgeBases("tenant-a")).thenReturn(List.of(knowledgeBase("tenant-a")));

        Response<List<RagKnowledgeBaseResponseDTO>> response = controller.list();

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals(1, response.getData().size());
        Assert.assertEquals("kb-a", response.getData().get(0).getKnowledgeBaseId());
        verify(repository).listKnowledgeBases("tenant-a");
    }

    @Test
    public void shouldReturnStableFailureWhenTrustedContextIsMissing() {
        IRagRepository repository = mock(IRagRepository.class);
        RagKnowledgeBaseManagementService service = new RagKnowledgeBaseManagementService(
                repository, new RagKnowledgeBaseAuthorizationService());
        RagKnowledgeBaseController controller = new RagKnowledgeBaseController(service);

        Response<List<RagKnowledgeBaseResponseDTO>> response = controller.list();

        Assert.assertEquals("RAG_AUTH_CONTEXT_MISSING", response.getCode());
        Assert.assertNull(response.getData());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    public void shouldRequireRevisionForKnowledgeBaseUpdate() {
        IRagRepository repository = mock(IRagRepository.class);
        RagKnowledgeBaseController controller = new RagKnowledgeBaseController(new RagKnowledgeBaseManagementService(
                repository, new RagKnowledgeBaseAuthorizationService()));
        TenantContextHolder.set(TenantContext.builder()
                .tenantId("tenant-a").userId("admin-1").roleCode("admin").build());
        RagKnowledgeBaseUpdateRequestDTO request = new RagKnowledgeBaseUpdateRequestDTO();
        request.setName("新名称");

        Response<RagKnowledgeBaseResponseDTO> response = controller.update("kb-a", request);

        Assert.assertEquals("RAG_KNOWLEDGE_BASE_REVISION_REQUIRED", response.getCode());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    public void shouldUpdateKnowledgeBaseInTrustedTenantAndReturnNextRevision() {
        IRagRepository repository = mock(IRagRepository.class);
        RagKnowledgeBaseController controller = new RagKnowledgeBaseController(new RagKnowledgeBaseManagementService(
                repository, new RagKnowledgeBaseAuthorizationService()));
        TenantContextHolder.set(TenantContext.builder()
                .tenantId("tenant-a").userId("admin-1").roleCode("admin").build());
        when(repository.findKnowledgeBase("tenant-a", "kb-a"))
                .thenReturn(Optional.of(knowledgeBase("tenant-a")));
        when(repository.listKnowledgeBases("tenant-a")).thenReturn(List.of(knowledgeBase("tenant-a")));
        when(repository.updateKnowledgeBase(org.mockito.ArgumentMatchers.eq("tenant-a"), any(),
                org.mockito.ArgumentMatchers.eq(0L))).thenReturn(1);
        RagKnowledgeBaseUpdateRequestDTO request = new RagKnowledgeBaseUpdateRequestDTO();
        request.setName("企业知识中心");
        request.setDescription("已更新");
        request.setExpectedRevision(0L);

        Response<RagKnowledgeBaseResponseDTO> response = controller.update("kb-a", request);

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals("企业知识中心", response.getData().getName());
        Assert.assertEquals("已更新", response.getData().getDescription());
        Assert.assertEquals(Long.valueOf(1L), response.getData().getRevision());
    }

    private RagKnowledgeBaseEntity knowledgeBase(String tenantId) {
        return new RagKnowledgeBaseEntity(tenantId, "owner-a", "kb-a", "企业手册", null,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null, 768,
                "rag_hash_kb-a", 0L, 0L);
    }
}
