package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadCommand;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentUploadResult;
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
}
