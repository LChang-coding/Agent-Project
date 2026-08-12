package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentToolPermissionRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.domain.agent.model.entity.AgentConfigStatusEntity;
import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.domain.agent.service.AgentToolPermissionService;
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
        AgentToolPermissionService service = new AgentToolPermissionService(repository, availability);

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
                Mockito.mock(IAgentToolPermissionRepository.class), Mockito.mock(AgentAvailabilityService.class));
        service.update("tenant-1", "user-1", "member", "parent-1", "create_subagent_instances",
                "DENY", 60, "REJECT", List.of(), null);
    }
}
