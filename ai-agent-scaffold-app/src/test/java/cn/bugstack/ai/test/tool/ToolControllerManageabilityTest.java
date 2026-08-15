package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.api.dto.tool.McpResponseDTO;
import cn.bugstack.ai.api.dto.tool.SkillResponseDTO;
import cn.bugstack.ai.domain.tool.model.entity.McpDefinitionEntity;
import cn.bugstack.ai.domain.tool.model.entity.SkillDefinitionEntity;
import cn.bugstack.ai.domain.tool.service.IToolPublishService;
import cn.bugstack.ai.trigger.http.ToolController;
import cn.bugstack.ai.types.context.TenantContext;
import cn.bugstack.ai.types.context.TenantContextHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;

/** 工具列表管理权响应契约测试。 */
public class ToolControllerManageabilityTest {

    @After
    public void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    public void shouldMarkOnlyOwnedPrivateResourcesManageableForMember() throws Exception {
        IToolPublishService service = Mockito.mock(IToolPublishService.class);
        Mockito.when(service.querySkills(Mockito.any(), Mockito.eq("available"))).thenReturn(List.of(
                skill("owned", "user-1", "private"),
                skill("foreign", "user-2", "private"),
                skill("public", "user-1", "tenant_public")));
        ToolController controller = controller(service);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-1").userId("user-1").roleCode("member").build());

        List<SkillResponseDTO> result = controller.querySkills("available").getData();

        Assert.assertEquals(Boolean.TRUE, result.get(0).getManageable());
        Assert.assertEquals(Boolean.FALSE, result.get(1).getManageable());
        Assert.assertEquals(Boolean.FALSE, result.get(2).getManageable());
    }

    @Test
    public void shouldAllowAdministratorToManageTenantMcp() throws Exception {
        IToolPublishService service = Mockito.mock(IToolPublishService.class);
        Mockito.when(service.queryMcps(Mockito.any(), Mockito.eq("tenant"))).thenReturn(List.of(
                McpDefinitionEntity.builder().mcpId("mcp-1").mcpName("时间 MCP")
                        .ownerUserId("user-2").visibility("tenant_public").status("active").build()));
        ToolController controller = controller(service);
        TenantContextHolder.set(TenantContext.builder().tenantId("tenant-1").userId("admin-1").roleCode("admin").build());

        List<McpResponseDTO> result = controller.queryMcps("tenant").getData();

        Assert.assertEquals(Boolean.TRUE, result.get(0).getManageable());
    }

    private ToolController controller(IToolPublishService service) throws Exception {
        ToolController controller = new ToolController();
        Field field = ToolController.class.getDeclaredField("toolPublishService");
        field.setAccessible(true);
        field.set(controller, service);
        return controller;
    }

    private SkillDefinitionEntity skill(String id, String ownerUserId, String visibility) {
        return SkillDefinitionEntity.builder().skillId(id).skillName(id).skillCode(id)
                .ownerUserId(ownerUserId).visibility(visibility).status("active").build();
    }
}
