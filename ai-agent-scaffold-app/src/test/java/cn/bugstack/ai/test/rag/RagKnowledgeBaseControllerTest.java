package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseResponseDTO;
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

    private RagKnowledgeBaseEntity knowledgeBase(String tenantId) {
        return new RagKnowledgeBaseEntity(tenantId, "owner-a", "kb-a", "企业手册", null,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, null, 768,
                "rag_hash_kb-a", 0L, 0L);
    }
}
