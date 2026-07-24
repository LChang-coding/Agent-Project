package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagSettingEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.types.observability.AiLog;
import org.springframework.stereotype.Service;

/**
 * 会话RAG设置服务。
 * <p>开关属于会话，知识库绑定仍属于Agent/Workflow，避免把知识库权限复制到会话。</p>
 */
@Service
public class SessionRagSettingService {

    private final SessionDomain sessionDomain;
    private final IRagRepository ragRepository;

    public SessionRagSettingService(SessionDomain sessionDomain, IRagRepository ragRepository) {
        this.sessionDomain = sessionDomain;
        this.ragRepository = ragRepository;
    }

    /** 查询会话RAG设置与当前绑定状态。 */
    public SessionRagSettingEntity query(String tenantId, String userId, String sessionId) {
        ChatSessionEntity session = sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        return toSetting(session);
    }

    /** 更新会话RAG设置并返回当前绑定状态。 */
    public SessionRagSettingEntity update(String tenantId, String userId, String sessionId, boolean enabled) {
        ChatSessionEntity session = sessionDomain.updateRagEnabled(tenantId, userId, sessionId, enabled);
        SessionRagSettingEntity setting = toSetting(session);
        AiLog.info(AiLog.chat().ragSettingChanged(session.getTenantId(), session.getUserId(),
                session.getSessionId(), setting.enabled(), setting.bindingConfigured()));
        return setting;
    }

    private SessionRagSettingEntity toSetting(ChatSessionEntity session) {
        RagBindingTargetType targetType = "workflow".equalsIgnoreCase(session.getSourceType())
                ? RagBindingTargetType.WORKFLOW : RagBindingTargetType.AGENT;
        boolean configured = !ragRepository.listBindings(session.getTenantId(), targetType,
                session.getAgentId()).isEmpty();
        return new SessionRagSettingEntity(session.getSessionId(), Boolean.TRUE.equals(session.getRagEnabled()),
                configured, targetType, session.getAgentId());
    }
}
