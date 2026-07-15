package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentTenantOverrideRepository;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.AgentAvailabilityService;
import cn.bugstack.ai.domain.agent.service.chat.ChatService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** Agent 公开入口授权边界测试。 */
public class ChatServiceAuthorizationTest {

    @Test
    public void shouldRejectWorkflowRuntimeAgentAtPublicCreateSessionEntry() {
        IAgentTenantOverrideRepository repository = Mockito.mock(IAgentTenantOverrideRepository.class);
        AgentAvailabilityService availability = new AgentAvailabilityService(repository, new AiAgentAutoConfigProperties());
        ChatService service = new ChatService();
        ReflectionTestUtils.setField(service, "agentAvailabilityService", availability);

        try {
            service.createSession("workflow:wf-1:v1:node-1", "user-1");
            Assert.fail("普通 Agent 入口不应接受工作流运行时 Agent ID");
        } catch (AppException e) {
            Assert.assertEquals("E0001", e.getCode());
        }

        Mockito.verifyNoInteractions(repository);
    }
}
