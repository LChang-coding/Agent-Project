package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.api.dto.rag.RagRetrievalProfileRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagRetrievalProfileResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.domain.rag.service.RagRetrievalConfigurationService;
import cn.bugstack.ai.trigger.http.RagRetrievalConfigurationController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 检索配置 Controller 可信上下文和枚举边界测试。 */
public class RagRetrievalConfigurationControllerTest {

    @After
    public void clear() {
        TenantContextHolder.clear();
    }

    @Test
    public void shouldCreateProfileUsingOnlyTrustedTenantIdentity() {
        IRagRepository repository = mock(IRagRepository.class);
        RagRetrievalConfigurationController controller = controller(repository);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a").userId("admin-a")
                .roleCode("admin").build());
        when(repository.insertRetrievalProfile(eq("tenant-a"), any())).thenReturn(1);

        Response<RagRetrievalProfileResponseDTO> response = controller.createProfile(request());

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals("hybrid", response.getData().getMode());
        ArgumentCaptor<RagRetrievalProfileEntity> captor = ArgumentCaptor.forClass(RagRetrievalProfileEntity.class);
        verify(repository).insertRetrievalProfile(eq("tenant-a"), captor.capture());
        Assert.assertEquals("tenant-a", captor.getValue().tenantId());
    }

    @Test
    public void shouldReturnStableFailureForUnsupportedMode() {
        IRagRepository repository = mock(IRagRepository.class);
        RagRetrievalConfigurationController controller = controller(repository);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a").userId("admin-a")
                .roleCode("admin").build());
        RagRetrievalProfileRequestDTO request = request();
        request.setMode("magic-search");

        Response<RagRetrievalProfileResponseDTO> response = controller.createProfile(request);

        Assert.assertEquals("RAG_PROFILE_INVALID", response.getCode());
        Assert.assertNull(response.getData());
    }

    @Test
    public void shouldRejectMissingTrustedContextBeforeRepositoryMutation() {
        IRagRepository repository = mock(IRagRepository.class);
        RagRetrievalConfigurationController controller = controller(repository);

        Response<RagRetrievalProfileResponseDTO> response = controller.createProfile(request());

        Assert.assertEquals("RAG_AUTH_CONTEXT_MISSING", response.getCode());
    }

    @Test
    public void shouldReturnStableFailureWhenBindingRevisionIsMissing() {
        IRagRepository repository = mock(IRagRepository.class);
        RagRetrievalConfigurationController controller = controller(repository);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a").userId("admin-a")
                .roleCode("admin").build());

        Response<Boolean> response = controller.deleteBinding("binding-a", null);

        Assert.assertEquals("RAG_BINDING_REVISION_REQUIRED", response.getCode());
    }

    private RagRetrievalConfigurationController controller(IRagRepository repository) {
        return new RagRetrievalConfigurationController(new RagRetrievalConfigurationService(repository,
                new RagKnowledgeBaseAuthorizationService()));
    }

    private RagRetrievalProfileRequestDTO request() {
        RagRetrievalProfileRequestDTO value = new RagRetrievalProfileRequestDTO();
        value.setName("混合检索");
        value.setMode("hybrid");
        value.setFusionStrategy("rrf");
        value.setDenseWeight(BigDecimal.ONE);
        value.setSparseWeight(BigDecimal.ONE);
        value.setDenseTopK(20);
        value.setSparseTopK(20);
        value.setFusionTopK(20);
        value.setRerankEnabled(true);
        value.setRerankTopK(10);
        value.setFinalTopK(5);
        value.setNeighborWindow(1);
        value.setMaxContextTokens(1024);
        value.setDeduplicateEnabled(true);
        return value;
    }
}
