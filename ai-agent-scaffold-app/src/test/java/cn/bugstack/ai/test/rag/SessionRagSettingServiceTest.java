package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagSettingEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.service.SessionRagSettingService;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SessionRagSettingServiceTest {

    @Test
    public void shouldUpdateSessionAndReportAgentBinding() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ChatSessionEntity session = session("agent", "agent-1", true);
        when(sessionDomain.updateRagEnabled("tenant-1", "user-1", "session-1", true)).thenReturn(session);
        when(repository.listBindings("tenant-1", RagBindingTargetType.AGENT, "agent-1"))
                .thenReturn(List.of(binding(RagBindingTargetType.AGENT, "agent-1")));

        SessionRagSettingEntity result = new SessionRagSettingService(sessionDomain, repository)
                .update("tenant-1", "user-1", "session-1", true);

        Assert.assertTrue(result.enabled());
        Assert.assertTrue(result.bindingConfigured());
        Assert.assertEquals(RagBindingTargetType.AGENT, result.targetType());
        verify(sessionDomain).updateRagEnabled("tenant-1", "user-1", "session-1", true);
    }

    @Test
    public void shouldReportMissingWorkflowBindingWithoutPretendingRagIsReady() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ChatSessionEntity session = session("workflow", "wf-1", true);
        when(sessionDomain.assertSessionAccess("tenant-1", "user-1", "session-1", null)).thenReturn(session);
        when(repository.listBindings("tenant-1", RagBindingTargetType.WORKFLOW, "wf-1")).thenReturn(List.of());

        SessionRagSettingEntity result = new SessionRagSettingService(sessionDomain, repository)
                .query("tenant-1", "user-1", "session-1");

        Assert.assertTrue(result.enabled());
        Assert.assertFalse(result.bindingConfigured());
        Assert.assertEquals(RagBindingTargetType.WORKFLOW, result.targetType());
    }

    private ChatSessionEntity session(String sourceType, String targetId, boolean enabled) {
        return ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .sourceType(sourceType).agentId(targetId).ragEnabled(enabled).build();
    }

    private RagAgentBindingEntity binding(RagBindingTargetType type, String targetId) {
        return new RagAgentBindingEntity("tenant-1", "binding-1", type, targetId,
                "kb-1", "profile-1", false, 1000, 0, 0);
    }
}
