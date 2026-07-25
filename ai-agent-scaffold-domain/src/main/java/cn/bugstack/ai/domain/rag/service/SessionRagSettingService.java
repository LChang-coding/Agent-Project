package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.ISessionRagSelectionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalProfileEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagEligibleBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagSettingEntity;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagRunSnapshotEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 会话RAG设置服务。
 * <p>开关属于会话，知识库绑定仍属于Agent/Workflow，避免把知识库权限复制到会话。</p>
 */
@Service
public class SessionRagSettingService {

    /** 手动模式限制单会话选择数量，避免运行快照和上下文无界增长。 */
    private static final int MAX_SELECTED_BINDINGS = 32;
    /** 会话域负责访问控制、加锁和策略 revision 推进。 */
    private final SessionDomain sessionDomain;
    /** 查询目标绑定、知识库和检索策略的实时状态。 */
    private final IRagRepository ragRepository;
    /** 单独持久化会话手动选择，避免污染目标级绑定。 */
    private final ISessionRagSelectionRepository selectionRepository;

    /** 注入会话域、RAG 仓储和会话选择仓储。 */
    public SessionRagSettingService(SessionDomain sessionDomain, IRagRepository ragRepository,
                                    ISessionRagSelectionRepository selectionRepository) {
        this.sessionDomain = sessionDomain;
        this.ragRepository = ragRepository;
        this.selectionRepository = selectionRepository;
    }

    /** 查询会话RAG设置与当前绑定状态。 */
    public SessionRagSettingEntity query(String tenantId, String userId, String sessionId) {
        ChatSessionEntity session = sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        return toSetting(session);
    }

    /**
     * 将会话策略解析为不可漂移的运行快照。
     * AUTO在此刻展开全部有效绑定；MANUAL重新校验选择，确保外部调用前失败。
     */
    public SessionRagRunSnapshotEntity resolveRunSnapshot(ChatSessionEntity session) {
        if (session == null) {
            throw new IllegalArgumentException("会话不能为空");
        }
        SessionRagSettingEntity setting = toSetting(session);
        if (setting.mode() == SessionRagMode.OFF) {
            return new SessionRagRunSnapshotEntity(SessionRagMode.OFF, setting.revision(), List.of());
        }
        List<String> effective = setting.mode() == SessionRagMode.AUTO
                ? setting.eligibleBindings().stream().map(SessionRagEligibleBindingEntity::bindingId).toList()
                : setting.selectedBindingIds();
        var eligibleIds = setting.eligibleBindings().stream()
                .map(SessionRagEligibleBindingEntity::bindingId).collect(Collectors.toSet());
        if (effective.isEmpty() || !eligibleIds.containsAll(effective)) {
            throw new AppException("SESSION_RAG_BINDING_UNAVAILABLE",
                    "当前会话没有可用的RAG绑定，请关闭RAG或重新选择绑定");
        }
        return new SessionRagRunSnapshotEntity(setting.mode(), setting.revision(), effective);
    }

    /** 更新会话RAG设置并返回当前绑定状态。 */
    public SessionRagSettingEntity update(String tenantId, String userId, String sessionId, boolean enabled) {
        return update(tenantId, userId, sessionId, null, enabled, null, null);
    }

    /**
     * 更新会话RAG策略并替换手动选择。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param requestedMode 请求模式
     * @param legacyEnabled 兼容旧客户端开关
     * @param selectedBindingIds 手动选择绑定
     * @param expectedRevision 客户端期望版本
     * @return 更新后的设置
     */
    @Transactional(rollbackFor = Exception.class)
    public SessionRagSettingEntity update(String tenantId, String userId, String sessionId,
                                          String requestedMode, Boolean legacyEnabled,
                                          List<String> selectedBindingIds, Long expectedRevision) {
        SessionRagMode mode = resolveRequestedMode(requestedMode, legacyEnabled);
        ChatSessionEntity locked = sessionDomain.lockSessionAccess(tenantId, userId, sessionId, null);
        RagBindingTargetType targetType = targetType(locked);
        List<RagAgentBindingEntity> eligible = eligibleBindings(locked, targetType).stream()
                .map(EligibleBinding::binding).toList();
        List<String> selected = validateSelections(mode, selectedBindingIds, eligible);
        ChatSessionEntity session = sessionDomain.updateRagPolicy(tenantId, userId, sessionId, mode, expectedRevision);
        selectionRepository.replaceSelections(session.getTenantId(), session.getUserId(), session.getSessionId(),
                targetType, session.getAgentId(), selected);
        SessionRagSettingEntity setting = toSetting(session);
        AiLog.info(AiLog.chat().ragSettingChanged(session.getTenantId(), session.getUserId(),
                session.getSessionId(), setting.enabled(), setting.bindingConfigured()));
        return setting;
    }

    /** 汇总会话模式、有效绑定、当前选择和配置可用性。 */
    private SessionRagSettingEntity toSetting(ChatSessionEntity session) {
        RagBindingTargetType targetType = targetType(session);
        SessionRagMode mode = SessionRagMode.resolve(session.getRagMode(), session.getRagEnabled());
        List<EligibleBinding> eligible = eligibleBindings(session, targetType);
        List<String> selected = selectionRepository.listSelectedBindingIds(
                session.getTenantId(), session.getUserId(), session.getSessionId());
        var selectedSet = new LinkedHashSet<>(selected);
        var eligibleIds = eligible.stream().map(value -> value.binding().bindingId()).collect(Collectors.toSet());
        boolean configured = mode == SessionRagMode.MANUAL
                ? !selected.isEmpty() && eligibleIds.containsAll(selected)
                : !eligible.isEmpty();
        List<SessionRagEligibleBindingEntity> summaries = eligible.stream()
                .map(value -> new SessionRagEligibleBindingEntity(value.binding().bindingId(),
                        value.knowledgeBase().knowledgeBaseId(), value.knowledgeBase().name(),
                        value.profile().profileId(), value.profile().name(),
                        value.knowledgeBase().status().name(), value.binding().required(),
                        value.binding().maxTokens(), value.binding().priority(),
                        value.binding().revision(), selectedSet.contains(value.binding().bindingId())))
                .toList();
        return new SessionRagSettingEntity(session.getSessionId(), mode.enabled(), mode,
                session.getRagRevision() == null ? 0L : session.getRagRevision(), configured,
                targetType, session.getAgentId(), selected, summaries);
    }

    /** 兼容旧布尔开关，但拒绝它与新三态模式互相冲突。 */
    private SessionRagMode resolveRequestedMode(String requestedMode, Boolean legacyEnabled) {
        if ((requestedMode == null || requestedMode.isBlank()) && legacyEnabled == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG模式或开关不能为空");
        }
        SessionRagMode mode;
        if (requestedMode == null || requestedMode.isBlank()) {
            mode = Boolean.TRUE.equals(legacyEnabled) ? SessionRagMode.AUTO : SessionRagMode.OFF;
        } else {
            try {
                mode = SessionRagMode.valueOf(requestedMode.trim().toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG模式仅支持OFF、AUTO或MANUAL");
            }
        }
        if (legacyEnabled != null && legacyEnabled != mode.enabled()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG模式与兼容开关冲突");
        }
        return mode;
    }

    /** 手动模式要求非空、去重、限量且全部属于当前有效绑定。 */
    private List<String> validateSelections(SessionRagMode mode, List<String> requested,
                                            List<RagAgentBindingEntity> eligible) {
        List<String> values = requested == null ? List.of() : requested.stream()
                .map(value -> value == null ? "" : value.trim()).toList();
        if (mode != SessionRagMode.MANUAL) {
            if (values.stream().anyMatch(value -> !value.isEmpty())) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "仅MANUAL模式可以选择绑定");
            }
            return List.of();
        }
        if (values.isEmpty() || values.stream().anyMatch(String::isEmpty)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "MANUAL模式至少选择一个绑定");
        }
        if (values.size() > MAX_SELECTED_BINDINGS) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "会话最多选择32个RAG绑定");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(values);
        if (unique.size() != values.size()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG绑定ID不能重复");
        }
        Map<String, RagAgentBindingEntity> eligibleById = eligible.stream()
                .collect(Collectors.toMap(RagAgentBindingEntity::bindingId, Function.identity()));
        if (!eligibleById.keySet().containsAll(unique)) {
            throw new AppException("SESSION_RAG_BINDING_UNAVAILABLE", "所选绑定不属于当前租户和运行目标或已停用");
        }
        return List.copyOf(unique);
    }

    /** 会话来源决定绑定目标类型；普通会话按 Agent 处理。 */
    private RagBindingTargetType targetType(ChatSessionEntity session) {
        return "workflow".equalsIgnoreCase(session.getSourceType())
                ? RagBindingTargetType.WORKFLOW : RagBindingTargetType.AGENT;
    }

    /**
     * 查询当前会话真正可检索的目标绑定。
     */
    private List<EligibleBinding> eligibleBindings(ChatSessionEntity session,
                                                   RagBindingTargetType targetType) {
        return ragRepository.listBindings(session.getTenantId(), targetType, session.getAgentId()).stream()
                .map(binding -> {
                    var knowledgeBase = ragRepository.findKnowledgeBase(
                            session.getTenantId(), binding.knowledgeBaseId());
                    var profile = ragRepository.findRetrievalProfile(
                            session.getTenantId(), binding.retrievalProfileId());
                    if (knowledgeBase.isEmpty() || profile.isEmpty()) {
                        return null;
                    }
                    RagKnowledgeBaseEntity value = knowledgeBase.get();
                    return value.status().searchable() && value.currentGeneration() > 0
                            && (value.visibility() != RagVisibility.PRIVATE
                            || value.ownerUserId().equals(session.getUserId()))
                            ? new EligibleBinding(binding, value, profile.get()) : null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 一条同时通过绑定、知识库生命周期和策略存在性校验的候选。 */
    private record EligibleBinding(RagAgentBindingEntity binding,
                                   RagKnowledgeBaseEntity knowledgeBase,
                                   RagRetrievalProfileEntity profile) {
    }
}
