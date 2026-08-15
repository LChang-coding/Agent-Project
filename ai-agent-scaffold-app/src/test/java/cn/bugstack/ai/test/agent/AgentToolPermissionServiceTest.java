package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentToolPermissionRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.domain.agent.model.entity.AgentConfigStatusEntity;
import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.domain.agent.service.AgentToolPermissionService;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCatalogEntity;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

public class AgentToolPermissionServiceTest {
    @Test
    public void shouldUseAllowDefaultAndPersistAdminApprovalPolicyWithRevision() {
        IAgentToolPermissionRepository repository = Mockito.mock(IAgentToolPermissionRepository.class);
        AgentAvailabilityService availability = Mockito.mock(AgentAvailabilityService.class);
        Mockito.when(availability.isStaticAgent("parent-1")).thenReturn(true);
        Mockito.when(availability.queryConfigs("tenant-1", true)).thenReturn(List.of(
                AgentConfigStatusEntity.builder().agentId("parent-1").orchestrationRole("SUPERVISOR").build()));
        Mockito.when(repository.query("tenant-1", "parent-1", "create_subagent_instances")).thenReturn(null);
        IToolRepository toolRepository = Mockito.mock(IToolRepository.class);
        Mockito.when(toolRepository.queryAvailableTools("tenant-1", "admin-1")).thenReturn(List.of());
        AgentToolPermissionService service = new AgentToolPermissionService(repository, availability, toolRepository);

        Assert.assertEquals("ALLOW", service.resolve("tenant-1", "parent-1",
                "create_subagent_instances").getMode());
        Mockito.when(repository.insert(Mockito.any())).thenReturn(1);
        AgentToolPermissionEntity saved = service.update("tenant-1", "admin-1", "admin", "parent-1",
                "create_subagent_instances", "REQUIRE_APPROVAL", 120, "REJECT",
                List.of("按建议参数创建", "减少子 Agent 数量"), null);

        Assert.assertEquals("REQUIRE_APPROVAL", saved.getMode());
        Assert.assertEquals(Long.valueOf(0), saved.getRevision());
        Mockito.verify(repository).insert(Mockito.any());
    }

    @Test(expected = RuntimeException.class)
    public void shouldRejectMemberMutation() {
        AgentToolPermissionService service = new AgentToolPermissionService(
                Mockito.mock(IAgentToolPermissionRepository.class), Mockito.mock(AgentAvailabilityService.class),
                Mockito.mock(IToolRepository.class));
        service.update("tenant-1", "user-1", "member", "parent-1", "create_subagent_instances",
                "DENY", 60, "REJECT", List.of(), null);
    }

    @Test
    public void shouldReturnPlatformMcpAndSkillPermissionsForAgent() {
        IAgentToolPermissionRepository repository = Mockito.mock(IAgentToolPermissionRepository.class);
        AgentAvailabilityService availability = Mockito.mock(AgentAvailabilityService.class);
        IToolRepository tools = Mockito.mock(IToolRepository.class);
        Mockito.when(availability.queryConfigs("tenant-1", true)).thenReturn(List.of(
                AgentConfigStatusEntity.builder().agentId("parent-1").orchestrationRole("SUPERVISOR").build()));
        Mockito.when(tools.queryAvailableTools("tenant-1", "admin-1")).thenReturn(List.of(
                ToolCatalogEntity.builder().toolType("mcp").toolId("mcp-1").toolName("时间 MCP").build(),
                ToolCatalogEntity.builder().toolType("skill").toolId("skill-1").toolCode("review")
                        .toolName("审查 Skill").build()));
        Mockito.when(repository.queryByAgent("tenant-1", "parent-1")).thenReturn(List.of());
        AgentToolPermissionService service = new AgentToolPermissionService(repository, availability, tools);

        List<AgentToolPermissionEntity> result = service.queryByAgent("tenant-1", "admin-1", "parent-1");

        Assert.assertTrue(result.stream().anyMatch(value -> "select_workflow_route".equals(value.getToolCode())));
        Assert.assertTrue(result.stream().anyMatch(value -> "create_subagent_instances".equals(value.getToolCode())));
        Assert.assertTrue(result.stream().anyMatch(value -> "mcp:mcp-1".equals(value.getToolCode())
                && "时间 MCP".equals(value.getToolName())));
        Assert.assertTrue(result.stream().anyMatch(value -> "skill:review".equals(value.getToolCode())
                && "审查 Skill".equals(value.getToolName())));
    }
}
