package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentTenantOverrideRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentConfigStatusEntity;
import cn.bugstack.ai.domain.agent.model.entity.AgentTenantOverrideEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

/** Agent 租户状态覆盖测试。 */
public class AgentAvailabilityServiceTest {

    @Test
    public void shouldIsolateDisabledOverrideByTenantAndHideFromRuntimeList() {
        IAgentTenantOverrideRepository repository = Mockito.mock(IAgentTenantOverrideRepository.class);
        AgentAvailabilityService service = new AgentAvailabilityService(repository, properties());
        AgentTenantOverrideEntity disabled = AgentTenantOverrideEntity.builder().tenantId("tenant-a")
                .agentId("agent-1").status("disabled").revision(2L).build();
        Mockito.when(repository.queryList("tenant-a")).thenReturn(List.of(disabled));
        Mockito.when(repository.queryList("tenant-b")).thenReturn(List.of());
        Mockito.when(repository.query("tenant-a", "agent-1")).thenReturn(disabled);
        Mockito.when(repository.query("tenant-b", "agent-1")).thenReturn(null);

        Assert.assertTrue(service.queryConfigs("tenant-a", false).isEmpty());
        List<AgentConfigStatusEntity> management = service.queryConfigs("tenant-a", true);
        Assert.assertEquals("disabled", management.get(0).getStatus());
        Assert.assertFalse(service.isEnabled("tenant-a", "agent-1"));
        Assert.assertTrue(service.isEnabled("tenant-b", "agent-1"));
    }

    @Test
    public void shouldRequireAdminAndUseOptimisticRevision() {
        IAgentTenantOverrideRepository repository = Mockito.mock(IAgentTenantOverrideRepository.class);
        AgentAvailabilityService service = new AgentAvailabilityService(repository, properties());
        try {
            service.updateStatus("tenant-a", "user-1", "member", "agent-1", "disabled", null, 0L);
            Assert.fail("普通成员不能禁用租户 Agent");
        } catch (AppException e) {
            Assert.assertEquals("AGENT_STATUS_PERMISSION_DENIED", e.getCode());
        }
        Mockito.when(repository.query("tenant-a", "agent-1")).thenReturn(null);
        Mockito.when(repository.insert(Mockito.any())).thenReturn(1);
        AgentTenantOverrideEntity result = service.updateStatus("tenant-a", "owner-1", "owner",
                "agent-1", "disabled", "控制台删除", 0L);
        Assert.assertEquals("disabled", result.getStatus());
        Assert.assertEquals(Long.valueOf(0L), result.getRevision());
        Mockito.verify(repository).insert(Mockito.argThat(item -> "tenant-a".equals(item.getTenantId())
                && "owner-1".equals(item.getUpdatedBy())));
    }

    private AiAgentAutoConfigProperties properties() {
        AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
        agent.setAgentId("agent-1"); agent.setAgentName("Agent 1"); agent.setAgentDesc("测试");
        AiAgentConfigTableVO table = new AiAgentConfigTableVO(); table.setAgent(agent);
        AiAgentAutoConfigProperties properties = new AiAgentAutoConfigProperties();
        properties.setTables(Map.of("agent-1", table));
        return properties;
    }
}
