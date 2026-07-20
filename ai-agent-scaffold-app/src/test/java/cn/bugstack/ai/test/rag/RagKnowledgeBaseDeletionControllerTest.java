package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseDeleteRequestDTO;
import cn.bugstack.ai.api.dto.rag.RagKnowledgeBaseDeleteTaskResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseDeletionService;
import cn.bugstack.ai.trigger.http.RagKnowledgeBaseDeletionController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** 知识库删除API的可信身份、revision与响应脱敏测试。 */
public class RagKnowledgeBaseDeletionControllerTest {
    @After
    public void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    public void shouldRequireRevisionBeforeCallingService() {
        RagKnowledgeBaseDeletionService service = Mockito.mock(RagKnowledgeBaseDeletionService.class);
        RagKnowledgeBaseDeletionController controller = new RagKnowledgeBaseDeletionController(service);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a")
                .userId("admin-a").roleCode("admin").build());

        Response<RagKnowledgeBaseDeleteTaskResponseDTO> response = controller.requestDeletion(
                "kb-a", new RagKnowledgeBaseDeleteRequestDTO());

        Assert.assertEquals("RAG_KNOWLEDGE_BASE_REVISION_REQUIRED", response.getCode());
        Mockito.verifyNoInteractions(service);
    }

    @Test
    public void shouldUseTrustedContextAndExposeOnlySafeProgress() {
        RagKnowledgeBaseDeletionService service = Mockito.mock(RagKnowledgeBaseDeletionService.class);
        RagKnowledgeBaseDeletionController controller = new RagKnowledgeBaseDeletionController(service);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a")
                .userId("admin-a").roleCode("admin").build());
        RagKnowledgeBaseDeleteTaskEntity task = RagKnowledgeBaseDeleteTaskEntity.pending(
                "tenant-a", "kb-a", "admin-a", "task-a", "a".repeat(64), 3, 5);
        Mockito.when(service.requestDeletion("tenant-a", "admin-a", "admin", "kb-a", 7L))
                .thenReturn(task);

        Response<RagKnowledgeBaseDeleteTaskResponseDTO> response = controller.requestDeletion(
                "kb-a", RagKnowledgeBaseDeleteRequestDTO.builder().expectedRevision(7L).build());

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals("task-a", response.getData().getTaskId());
        Assert.assertEquals("pending", response.getData().getStatus());
        Assert.assertEquals(Integer.valueOf(3), response.getData().getTotalDocuments());
        Assert.assertNull(response.getData().getErrorMessage());
        Assert.assertFalse(java.util.Arrays.stream(response.getData().getClass().getDeclaredFields())
                .anyMatch(field -> field.getName().toLowerCase().contains("lease")
                        || field.getName().toLowerCase().contains("fencing")));
    }

    @Test
    public void shouldRestoreDeleteTaskByKnowledgeBaseWithTrustedContext() {
        RagKnowledgeBaseDeletionService service = Mockito.mock(RagKnowledgeBaseDeletionService.class);
        RagKnowledgeBaseDeletionController controller = new RagKnowledgeBaseDeletionController(service);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a")
                .userId("admin-a").roleCode("admin").build());
        RagKnowledgeBaseDeleteTaskEntity task = RagKnowledgeBaseDeleteTaskEntity.pending(
                "tenant-a", "kb-a", "admin-a", "task-a", "a".repeat(64), 3, 5);
        Mockito.when(service.requireTaskByKnowledgeBase("tenant-a", "admin-a", "admin", "kb-a"))
                .thenReturn(task);

        Response<RagKnowledgeBaseDeleteTaskResponseDTO> response =
                controller.taskByKnowledgeBase("kb-a");

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals("task-a", response.getData().getTaskId());
        Mockito.verify(service).requireTaskByKnowledgeBase("tenant-a", "admin-a", "admin", "kb-a");
    }
}
