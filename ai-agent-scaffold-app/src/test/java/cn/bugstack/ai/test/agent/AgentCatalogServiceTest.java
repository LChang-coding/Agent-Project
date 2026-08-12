package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.model.entity.AgentConfigStatusEntity;
import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.domain.agent.service.AgentCatalogService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

public class AgentCatalogServiceTest {

    @Test
    public void searchesAuthorizedTemplateByPlainOrPrefixedAgentId() {
        AgentAvailabilityService availability = Mockito.mock(AgentAvailabilityService.class);
        Mockito.when(availability.queryConfigs("tenant", false)).thenReturn(List.of(
                AgentConfigStatusEntity.builder().agentId("100001").agentName("代码 Agent")
                        .agentDesc("Java 实现与审查").category("CODING")
                        .bestFor(List.of("Java 开发")).capabilities(List.of("coding")).build(),
                AgentConfigStatusEntity.builder().agentId("100002").agentName("研究 Agent")
                        .agentDesc("资料研究").category("RESEARCH")
                        .bestFor(List.of("资料调研")).capabilities(List.of("research")).build()));
        AgentCatalogService service = new AgentCatalogService(availability);

        Assert.assertEquals("100001", service.search("tenant", List.of("100001", "100002"),
                "100001", null).get(0).agentId());
        Assert.assertEquals("100002", service.search("tenant", List.of("100001", "100002"),
                "Agent100002", null).get(0).agentId());
    }
}
