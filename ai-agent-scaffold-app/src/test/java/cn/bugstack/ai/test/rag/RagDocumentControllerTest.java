package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadResult;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestOperation;
import cn.bugstack.ai.domain.rag.service.RagDocumentManagementService;
import cn.bugstack.ai.domain.rag.service.RagDocumentUploadService;
import cn.bugstack.ai.trigger.http.RagDocumentController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文档 HTTP 适配层的可信上下文与临时文件生命周期测试。 */
public class RagDocumentControllerTest {

    @After
    public void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    public void shouldStageMultipartInvokeTrustedCommandAndDeleteTemporaryFile() {
        RagDocumentUploadService uploadService = mock(RagDocumentUploadService.class);
        RagDocumentManagementService managementService = mock(RagDocumentManagementService.class);
        when(uploadService.upload(any())).thenReturn(new RagDocumentUploadResult(
                "doc-a", "ver-a", "task-a", "知识.md", 8, "queued", false));
        RagDocumentController controller = new RagDocumentController(uploadService, managementService);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a").userId("user-a")
                .roleCode("admin").build());
        MockMultipartFile file = new MockMultipartFile("file", "知识.md", "text/markdown",
                "# 内容".getBytes(StandardCharsets.UTF_8));

        var response = controller.upload("kb-a", file);

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals("task-a", response.getData().getTaskId());
        ArgumentCaptor<RagDocumentUploadCommand> command = ArgumentCaptor.forClass(RagDocumentUploadCommand.class);
        verify(uploadService).upload(command.capture());
        Assert.assertEquals("tenant-a", command.getValue().tenantId());
        Assert.assertEquals("user-a", command.getValue().userId());
        Assert.assertEquals("admin", command.getValue().roleCode());
        Assert.assertEquals("kb-a", command.getValue().knowledgeBaseId());
        Assert.assertFalse("Controller finally 必须删除暂存文件",
                java.nio.file.Files.exists(command.getValue().file().path()));
    }

    @Test
    public void shouldListTasksUsingTrustedContextAndPublicProjection() {
        RagDocumentUploadService uploadService = mock(RagDocumentUploadService.class);
        RagDocumentManagementService managementService = mock(RagDocumentManagementService.class);
        var task = RagIngestJobEntity.pending("tenant-a", "kb-a", "doc-a", "ver-a", "task-a",
                "task-key", RagIngestOperation.INGEST, 1, 3);
        when(managementService.listTasks("tenant-a", "admin-a", "admin", "kb-a", 25))
                .thenReturn(List.of(task));
        RagDocumentController controller = new RagDocumentController(uploadService, managementService);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-a").userId("admin-a")
                .roleCode("admin").build());

        var response = controller.tasks("kb-a", 25);

        Assert.assertEquals("0000", response.getCode());
        Assert.assertEquals(1, response.getData().size());
        Assert.assertEquals("task-a", response.getData().get(0).getTaskId());
        List<String> publicFields = Arrays.stream(response.getData().get(0).getClass().getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        Assert.assertFalse(publicFields.contains("leaseOwner"));
        Assert.assertFalse(publicFields.contains("fencingToken"));
        Assert.assertFalse(publicFields.contains("errorMessage"));
        verify(managementService).listTasks("tenant-a", "admin-a", "admin", "kb-a", 25);
    }
}
