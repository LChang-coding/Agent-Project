package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.McpDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.McpVersionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadCommandEntity;
import cn.bugstack.ai.domain.tool.model.entity.ToolUserContextEntity;
import cn.bugstack.ai.domain.tool.service.ToolPublishService;
import cn.bugstack.ai.domain.tool.service.mcp.McpProtocolClientSupport;
import cn.bugstack.ai.domain.tool.service.support.SkillPackageReader;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * stdio MCP 发布服务测试。
 */
public class ToolPublishServiceStdioTest {

    /**
     * 验证测试失败的 MCP 不能发布；无参数；无返回值。
     */
    @Test
    public void shouldRejectPublishingMcpWhenTestFailed() {
        IToolRepository repository = mock(IToolRepository.class);
        McpProtocolClientSupport protocolClientSupport = new McpProtocolClientSupport(java.util.List.of());
        ToolPublishService service = new ToolPublishService(repository, null, protocolClientSupport,
                mock(SkillPackageReader.class));
        ToolUserContextEntity context = ToolUserContextEntity.builder()
                .tenantId("tenant_001")
                .userId("user_001")
                .roleCode("owner")
                .build();
        McpDefinitionEntity mcp = McpDefinitionEntity.builder()
                .mcpId("mcp_001")
                .ownerUserId("user_001")
                .visibility("private")
                .currentVersion("1.0.0")
                .build();
        McpVersionEntity version = McpVersionEntity.builder()
                .mcpId("mcp_001")
                .version("1.0.0")
                .testStatus("failed")
                .build();
        when(repository.queryMcpDefinition("mcp_001")).thenReturn(mcp);
        when(repository.queryMcpVersion("mcp_001", "1.0.0")).thenReturn(version);

        try {
            service.publishMcp(context, "mcp_001", "1.0.0");
            Assert.fail("测试失败的 MCP 不允许发布");
        } catch (AppException e) {
            Assert.assertEquals("TOOL_MCP_TEST_NOT_PASSED", e.getCode());
        }
    }

    @Test
    public void shouldUseSharedReaderBeforePersistingSkillPackage() {
        IToolRepository repository = mock(IToolRepository.class);
        SkillPackageReader reader = mock(SkillPackageReader.class);
        byte[] bytes = new byte[]{1, 2, 3};
        AppException expected = new AppException("TOOL_SKILL_PACKAGE_INVALID", "SKILL.md 超限");
        org.mockito.Mockito.doThrow(expected).when(reader).readSkillMd(bytes);
        ToolPublishService service = new ToolPublishService(repository, null,
                new McpProtocolClientSupport(java.util.List.of()), reader);

        try {
            service.uploadSkillPackage(SkillPackageUploadCommandEntity.builder()
                    .context(ToolUserContextEntity.builder().tenantId("tenant_001").userId("user_001").build())
                    .fileName("skill.zip").bytes(bytes).build());
            Assert.fail("读取器拒绝后不应继续上传");
        } catch (AppException error) {
            Assert.assertSame(expected, error);
        }
        verifyNoInteractions(repository);
    }
}
