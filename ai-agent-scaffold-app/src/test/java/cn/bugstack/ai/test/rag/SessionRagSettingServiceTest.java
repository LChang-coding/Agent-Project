package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.ISessionRagSelectionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagSettingEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagFusionStrategy;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagRetrievalMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import cn.bugstack.ai.domain.rag.service.SessionRagSettingService;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SessionRagSettingServiceTest {

    @Test
    public void shouldUpdateSessionAndReportAgentBinding() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ISessionRagSelectionRepository selections = mock(ISessionRagSelectionRepository.class);
        ChatSessionEntity session = session("agent", "agent-1", SessionRagMode.AUTO, 1L);
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", null)).thenReturn(session);
        when(sessionDomain.updateRagPolicy("tenant-1", "user-1", "session-1",
                SessionRagMode.AUTO, null)).thenReturn(session);
        when(sessionDomain.updateRagPolicy("tenant-1", "user-1", "session-1",
                SessionRagMode.AUTO, null, null)).thenReturn(session);
        when(repository.listBindings("tenant-1", RagBindingTargetType.AGENT, "agent-1"))
                .thenReturn(List.of(binding(RagBindingTargetType.AGENT, "agent-1")));
        stubUsableBinding(repository);
        when(selections.listSelectedBindingIds("tenant-1", "user-1", "session-1")).thenReturn(List.of());

        SessionRagSettingEntity result = new SessionRagSettingService(sessionDomain, repository, selections)
                .update("tenant-1", "user-1", "session-1", true);

        Assert.assertTrue(result.enabled());
        Assert.assertEquals(SessionRagMode.AUTO, result.mode());
        Assert.assertTrue(result.bindingConfigured());
        Assert.assertEquals(RagBindingTargetType.AGENT, result.targetType());
        verify(sessionDomain).updateRagPolicy("tenant-1", "user-1", "session-1",
                SessionRagMode.AUTO, null, null);
        verify(selections).replaceSelections("tenant-1", "user-1", "session-1",
                RagBindingTargetType.AGENT, "agent-1", List.of());
    }

    @Test
    public void shouldReportMissingWorkflowBindingWithoutPretendingRagIsReady() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ISessionRagSelectionRepository selections = mock(ISessionRagSelectionRepository.class);
        ChatSessionEntity session = session("workflow", "wf-1", SessionRagMode.AUTO, 2L);
        when(sessionDomain.assertSessionAccess("tenant-1", "user-1", "session-1", null)).thenReturn(session);
        when(repository.listBindings("tenant-1", RagBindingTargetType.WORKFLOW, "wf-1")).thenReturn(List.of());
        when(selections.listSelectedBindingIds("tenant-1", "user-1", "session-1")).thenReturn(List.of());

        SessionRagSettingEntity result = new SessionRagSettingService(sessionDomain, repository, selections)
                .query("tenant-1", "user-1", "session-1");

        Assert.assertTrue(result.enabled());
        Assert.assertFalse(result.bindingConfigured());
        Assert.assertEquals(RagBindingTargetType.WORKFLOW, result.targetType());
    }

    @Test
    public void shouldPersistValidatedManualSelections() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ISessionRagSelectionRepository selections = mock(ISessionRagSelectionRepository.class);
        ChatSessionEntity before = session("agent", "agent-1", SessionRagMode.OFF, 3L);
        ChatSessionEntity after = session("agent", "agent-1", SessionRagMode.MANUAL, 4L);
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", null)).thenReturn(before);
        when(sessionDomain.updateRagPolicy("tenant-1", "user-1", "session-1",
                SessionRagMode.MANUAL, 3L)).thenReturn(after);
        when(sessionDomain.updateRagPolicy("tenant-1", "user-1", "session-1",
                SessionRagMode.MANUAL, null, 3L)).thenReturn(after);
        when(repository.listBindings("tenant-1", RagBindingTargetType.AGENT, "agent-1"))
                .thenReturn(List.of(binding(RagBindingTargetType.AGENT, "agent-1")));
        stubUsableBinding(repository);
        when(selections.listSelectedBindingIds("tenant-1", "user-1", "session-1"))
                .thenReturn(List.of("binding-1"));

        SessionRagSettingEntity result = new SessionRagSettingService(sessionDomain, repository, selections)
                .update("tenant-1", "user-1", "session-1", "MANUAL", null,
                        List.of("binding-1"), 3L);

        Assert.assertEquals(SessionRagMode.MANUAL, result.mode());
        Assert.assertEquals(List.of("binding-1"), result.selectedBindingIds());
        Assert.assertTrue(result.eligibleBindings().get(0).selected());
        verify(selections).replaceSelections("tenant-1", "user-1", "session-1",
                RagBindingTargetType.AGENT, "agent-1", List.of("binding-1"));
    }

    @Test
    public void shouldRejectUnavailableManualBinding() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ISessionRagSelectionRepository selections = mock(ISessionRagSelectionRepository.class);
        ChatSessionEntity session = session("workflow", "wf-1", SessionRagMode.OFF, 0L);
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", null)).thenReturn(session);
        when(repository.listBindings("tenant-1", RagBindingTargetType.WORKFLOW, "wf-1"))
                .thenReturn(List.of());

        try {
            new SessionRagSettingService(sessionDomain, repository, selections)
                    .update("tenant-1", "user-1", "session-1", "MANUAL", null,
                            List.of("other-target-binding"), 0L);
            Assert.fail("跨目标或不可用绑定不应写入");
        } catch (AppException exception) {
            Assert.assertEquals("SESSION_RAG_BINDING_UNAVAILABLE", exception.getCode());
        }
    }

    @Test
    public void shouldRejectBindingWhoseKnowledgeBaseIsNotSearchable() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ISessionRagSelectionRepository selections = mock(ISessionRagSelectionRepository.class);
        ChatSessionEntity session = session("agent", "agent-1", SessionRagMode.OFF, 0L);
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", null)).thenReturn(session);
        when(repository.listBindings("tenant-1", RagBindingTargetType.AGENT, "agent-1"))
                .thenReturn(List.of(binding(RagBindingTargetType.AGENT, "agent-1")));
        when(repository.findKnowledgeBase("tenant-1", "kb-1")).thenReturn(Optional.of(
                new RagKnowledgeBaseEntity("tenant-1", "owner-1", "kb-1", "知识库", null,
                        RagVisibility.TENANT, RagKnowledgeBaseStatus.DISABLED, "profile-1",
                        768, "kb-1", 1L, 1L)));

        try {
            new SessionRagSettingService(sessionDomain, repository, selections)
                    .update("tenant-1", "user-1", "session-1", "MANUAL", null,
                            List.of("binding-1"), 0L);
            Assert.fail("不可检索知识库的绑定不应进入会话选择");
        } catch (AppException exception) {
            Assert.assertEquals("SESSION_RAG_BINDING_UNAVAILABLE", exception.getCode());
        }
    }

    @Test
    public void shouldExpandAutoBindingsIntoRunSnapshot() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ISessionRagSelectionRepository selections = mock(ISessionRagSelectionRepository.class);
        ChatSessionEntity session = session("agent", "agent-1", SessionRagMode.AUTO, 7L);
        when(repository.listBindings("tenant-1", RagBindingTargetType.AGENT, "agent-1"))
                .thenReturn(List.of(binding(RagBindingTargetType.AGENT, "agent-1")));
        stubUsableBinding(repository);
        when(selections.listSelectedBindingIds("tenant-1", "user-1", "session-1")).thenReturn(List.of());

        var snapshot = new SessionRagSettingService(sessionDomain, repository, selections)
                .resolveRunSnapshot(session);

        Assert.assertEquals(SessionRagMode.AUTO, snapshot.mode());
        Assert.assertEquals(7L, snapshot.revision());
        Assert.assertEquals(List.of("binding-1"), snapshot.bindingIds());
    }

    @Test
    public void shouldRejectManualRunSnapshotWhenSelectionBecameUnavailable() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        IRagRepository repository = mock(IRagRepository.class);
        ISessionRagSelectionRepository selections = mock(ISessionRagSelectionRepository.class);
        ChatSessionEntity session = session("workflow", "wf-1", SessionRagMode.MANUAL, 8L);
        when(repository.listBindings("tenant-1", RagBindingTargetType.WORKFLOW, "wf-1"))
                .thenReturn(List.of());
        when(selections.listSelectedBindingIds("tenant-1", "user-1", "session-1"))
                .thenReturn(List.of("binding-stale"));

        try {
            new SessionRagSettingService(sessionDomain, repository, selections).resolveRunSnapshot(session);
            Assert.fail("运行创建前必须拒绝已经失效的手动绑定");
        } catch (AppException exception) {
            Assert.assertEquals("SESSION_RAG_BINDING_UNAVAILABLE", exception.getCode());
        }
    }

    private ChatSessionEntity session(String sourceType, String targetId, SessionRagMode mode, long revision) {
        return ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .sourceType(sourceType).agentId(targetId).ragEnabled(mode.enabled())
                .ragMode(mode.name()).ragRevision(revision).build();
    }

    private RagAgentBindingEntity binding(RagBindingTargetType type, String targetId) {
        return new RagAgentBindingEntity("tenant-1", "binding-1", type, targetId,
                "kb-1", "profile-1", false, 1000, 0, 0);
    }

    private void stubUsableBinding(IRagRepository repository) {
        when(repository.findKnowledgeBase("tenant-1", "kb-1")).thenReturn(Optional.of(
                new RagKnowledgeBaseEntity("tenant-1", "owner-1", "kb-1", "知识库", null,
                        RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, "profile-1",
                        768, "kb-1", 1L, 1L)));
        when(repository.findRetrievalProfile("tenant-1", "profile-1")).thenReturn(Optional.of(
                new RagRetrievalProfileEntity("tenant-1", "profile-1", "默认策略",
                        RagRetrievalMode.DENSE, RagFusionStrategy.NONE, BigDecimal.ONE, BigDecimal.ZERO,
                        10, 0, 10, false, 0, 5, 0, 2048,
                        BigDecimal.ZERO, false, true, 1L)));
    }
}
