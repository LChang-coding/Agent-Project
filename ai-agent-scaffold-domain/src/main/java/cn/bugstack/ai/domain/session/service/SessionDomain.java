package cn.bugstack.ai.domain.session.service;

import cn.bugstack.ai.domain.session.adapter.repository.ISessionRepository;
import cn.bugstack.ai.domain.context.service.CharacterTokenCounter;
import cn.bugstack.ai.domain.session.model.entity.AppendMessageCommandEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.CreateSessionCommandEntity;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import cn.bugstack.ai.types.observability.AiLogFields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

/**
 * 会话聚合领域服务。
 * <p>集中维护会话所有权、消息顺序、有效性和上下文/RAG版本。</p>
 */
@Service
public class SessionDomain {

    /** 持久化用户输入消息使用的角色值。 */
    public static final String ROLE_USER = "user";
    /** 持久化模型回答消息使用的角色值。 */
    public static final String ROLE_ASSISTANT = "assistant";
    /** 平台内部恢复输入：可审计，但不属于用户发言。 */
    public static final String ROLE_PLATFORM = "platform";
    /** 新建且未删除会话的持久化状态。 */
    private static final String STATUS_ACTIVE = "active";
    /** 当前会话消息统一保存的文本内容类型。 */
    private static final String CONTENT_TYPE_TEXT = "text";
    /** 保存消息时使用统一字符口径估算 Token 数。 */
    private static final CharacterTokenCounter TOKEN_COUNTER = new CharacterTokenCounter();

    /** 在租户和用户范围内持久化会话、消息及版本号。 */
    private final ISessionRepository sessionRepository;

    /**
     * 创建会话领域服务。
     */
    public SessionDomain(ISessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 创建平台会话。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionEntity createSession(CreateSessionCommandEntity command) {
        checkCreateCommand(command);
        LocalDateTime now = LocalDateTime.now();
        ChatSessionEntity session = ChatSessionEntity.builder()
                .tenantId(blankToNull(command.getTenantId()))
                .userId(command.getUserId().trim())
                .sessionId(command.getSessionId().trim())
                .agentId(command.getAgentId().trim())
                .agentName(command.getAgentName())
                .sourceType(blankToDefault(command.getSourceType(), "agent"))
                .workflowVersion(command.getWorkflowVersion())
                .modelCode(blankToNull(command.getModelCode()))
                .appName(command.getAppName())
                .title(blankToDefault(command.getTitle(), command.getAgentName()))
                .status(STATUS_ACTIVE)
                .ragEnabled(false)
                .ragMode(SessionRagMode.OFF.name())
                .ragInvocationMode(RagInvocationMode.AUTO_CONTEXT.name())
                .ragRevision(0L)
                .lastMessageTime(now)
                .contextRevision(0L)
                .build();
        // 新会话默认关闭 RAG，并从零版本开始，避免继承客户端或浏览器缓存状态。
        sessionRepository.insertSession(session);
        AiLog.info(AiLog.chat().sessionCreated(session.getTenantId(), session.getUserId(), session.getSessionId(),
                session.getAgentId(), session.getAgentName(), session.getAppName()));
        return session;
    }

    /**
     * 校验会话归属。
     */
    public ChatSessionEntity assertSessionAccess(String tenantId, String userId, String sessionId, String agentId) {
        checkSessionIdentity(userId, sessionId);
        ChatSessionEntity session = sessionRepository.querySession(blankToNull(tenantId), userId, sessionId);
        if (session == null) {
            AiLog.error(AiLog.chat().sessionRejected(blankToNull(tenantId), userId, sessionId, agentId,
                    ResponseCode.SESSION_NOT_FOUND.getCode(), ResponseCode.SESSION_NOT_FOUND.getInfo()));
            throw new AppException(ResponseCode.SESSION_NOT_FOUND.getCode(), ResponseCode.SESSION_NOT_FOUND.getInfo());
        }
        if (!isBlank(agentId) && !agentId.equals(session.getAgentId())) {
            AiLog.error(AiLog.chat().sessionRejected(blankToNull(tenantId), userId, sessionId, agentId,
                    ResponseCode.SESSION_ACCESS_DENIED.getCode(), ResponseCode.SESSION_ACCESS_DENIED.getInfo()));
            throw new AppException(ResponseCode.SESSION_ACCESS_DENIED.getCode(), ResponseCode.SESSION_ACCESS_DENIED.getInfo());
        }
        return session;
    }

    /**
     * 锁定并校验会话归属。
     */
    public ChatSessionEntity lockSessionAccess(String tenantId, String userId, String sessionId, String agentId) {
        checkSessionIdentity(userId, sessionId);
        ChatSessionEntity session = sessionRepository.lockSession(blankToNull(tenantId), userId, sessionId);
        if (session == null) {
            throw new AppException(ResponseCode.SESSION_NOT_FOUND.getCode(), ResponseCode.SESSION_NOT_FOUND.getInfo());
        }
        if (!isBlank(agentId) && !agentId.equals(session.getAgentId())) {
            throw new AppException(ResponseCode.SESSION_ACCESS_DENIED.getCode(), ResponseCode.SESSION_ACCESS_DENIED.getInfo());
        }
        return session;
    }

    /**
     * 保存用户消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendUserMessage(String tenantId, String userId, String sessionId, String content, String traceId) {
        return appendUserMessage(tenantId, userId, sessionId, null, content, traceId);
    }

    /**
     * 保存运行用户消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendUserMessage(String tenantId, String userId, String sessionId, String runId,
                                               String content, String traceId) {
        AppendMessageCommandEntity command = new AppendMessageCommandEntity();
        command.setTenantId(tenantId);
        command.setUserId(userId);
        command.setSessionId(sessionId);
        command.setRunId(runId);
        command.setRole(ROLE_USER);
        command.setContentType(CONTENT_TYPE_TEXT);
        command.setContent(content);
        command.setTraceId(traceId);
        return appendMessage(command);
    }

    /** 保存平台内部输入，用于运行绑定和审计，不对用户展示。 */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendPlatformMessage(String tenantId, String userId, String sessionId, String runId,
                                                    String content, String traceId) {
        AppendMessageCommandEntity command = new AppendMessageCommandEntity();
        command.setTenantId(tenantId);
        command.setUserId(userId);
        command.setSessionId(sessionId);
        command.setRunId(runId);
        command.setRole(ROLE_PLATFORM);
        command.setContentType(CONTENT_TYPE_TEXT);
        command.setContent(content);
        command.setTraceId(traceId);
        return appendMessage(command);
    }

    /**
     * 保存助手消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendAssistantMessage(String tenantId, String userId, String sessionId, String content, String traceId) {
        return appendAssistantMessage(tenantId, userId, sessionId, null, content, traceId);
    }

    /**
     * 保存运行助手消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendAssistantMessage(String tenantId, String userId, String sessionId, String runId,
                                                    String content, String traceId) {
        return appendAssistantMessage(tenantId, userId, sessionId, runId, content, traceId, null);
    }

    /** 保存带安全元数据的运行助手消息。 */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendAssistantMessage(String tenantId, String userId, String sessionId, String runId,
                                                     String content, String traceId, String metadata) {
        AppendMessageCommandEntity command = new AppendMessageCommandEntity();
        command.setTenantId(tenantId);
        command.setUserId(userId);
        command.setSessionId(sessionId);
        command.setRunId(runId);
        command.setRole(ROLE_ASSISTANT);
        command.setContentType(CONTENT_TYPE_TEXT);
        command.setContent(content);
        command.setTraceId(traceId);
        command.setMetadata(metadata);
        return appendMessage(command);
    }

    /**
     * 保存消息。
     */
    private ChatMessageEntity appendMessage(AppendMessageCommandEntity command) {
        checkMessageCommand(command);
        String tenantId = blankToNull(command.getTenantId());
        // 锁住会话后读取最大序号，保证并发消息不会获得相同 sequenceNo。
        ChatSessionEntity session = sessionRepository.lockSession(tenantId, command.getUserId(), command.getSessionId());
        if (session == null) {
            AiLog.error(AiLog.chat().sessionRejected(tenantId, command.getUserId(), command.getSessionId(), null,
                    ResponseCode.SESSION_NOT_FOUND.getCode(), ResponseCode.SESSION_NOT_FOUND.getInfo()));
            throw new AppException(ResponseCode.SESSION_NOT_FOUND.getCode(), ResponseCode.SESSION_NOT_FOUND.getInfo());
        }
        Integer maxSequenceNo = sessionRepository.queryMaxSequenceNo(session.getTenantId(), session.getUserId(), session.getSessionId());
        LocalDateTime now = LocalDateTime.now();
        ChatMessageEntity message = ChatMessageEntity.builder()
                .tenantId(session.getTenantId())
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .messageId("msg_" + UUID.randomUUID())
                .runId(command.getRunId())
                .validityStatus("active")
                .role(command.getRole())
                .contentType(command.getContentType())
                .content(command.getContent())
                .estimatedTokenCount(TOKEN_COUNTER.estimate(command.getContent()))
                .sequenceNo(maxSequenceNo == null ? 1 : maxSequenceNo + 1)
                .parentMessageId(command.getParentMessageId())
                .traceId(command.getTraceId())
                .metadata(command.getMetadata())
                .build();
        // 消息与最后活跃时间在调用方事务中共同提交。
        sessionRepository.insertMessage(message);
        sessionRepository.updateLastMessageTime(session.getTenantId(), session.getUserId(), session.getSessionId(), now);
        AiLog.info(AiLog.chat().messageSaved(message.getTenantId(), message.getUserId(), message.getSessionId(),
                message.getMessageId(), message.getRole(), message.getSequenceNo(), contentLength(message.getContent()))
                .field(AiLogFields.TRACE_ID, message.getTraceId()));
        return message;
    }

    /**
     * 推进会话上下文版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public long incrementContextRevision(String tenantId, String userId, String sessionId) {
        ChatSessionEntity session = sessionRepository.lockSession(blankToNull(tenantId), userId, sessionId);
        if (session == null) {
            throw new AppException(ResponseCode.SESSION_NOT_FOUND.getCode(), ResponseCode.SESSION_NOT_FOUND.getInfo());
        }
        return sessionRepository.incrementContextRevision(session.getTenantId(), session.getUserId(), session.getSessionId());
    }

    /**
     * 失效运行消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public int invalidateRunMessages(String tenantId, String userId, String sessionId, String runId, String reason) {
        return sessionRepository.invalidateRunMessages(blankToNull(tenantId), userId, sessionId, runId,
                reason, LocalDateTime.now());
    }

    /**
     * 查询运行消息。
     */
    public List<ChatMessageEntity> queryRunMessages(String tenantId, String userId, String sessionId, String runId) {
        return sessionRepository.queryRunMessages(blankToNull(tenantId), userId, sessionId, runId);
    }

    /** 按可信复合范围查询单条有效消息。 */
    public ChatMessageEntity queryValidMessage(String tenantId, String userId, String sessionId, String messageId) {
        assertSessionAccess(tenantId, userId, sessionId, null);
        return sessionRepository.queryValidMessage(blankToNull(tenantId), userId, sessionId, messageId);
    }

    /**
     * 查询会话有效消息。
     */
    public List<ChatMessageEntity> queryValidMessages(String tenantId, String userId, String sessionId) {
        // 所有上下文、分享和历史读取统一排除被取消或引导失效的消息。
        assertSessionAccess(tenantId, userId, sessionId, null);
        return sessionRepository.queryValidMessages(blankToNull(tenantId), userId, sessionId);
    }

    /**
     * 查询会话有效消息最大序号。
     */
    public Integer queryMaxValidSequenceNo(String tenantId, String userId, String sessionId) {
        return sessionRepository.queryMaxValidSequenceNo(blankToNull(tenantId), userId, sessionId);
    }

    /**
     * 游标查询用户会话。
     */
    public List<ChatSessionEntity> querySessions(String tenantId, String userId, LocalDateTime cursorTime,
                                                 String cursorSessionId, int limit) {
        if (isBlank(userId) || limit < 1 || limit > 101) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID或分页数量不合法");
        }
        return sessionRepository.querySessions(blankToNull(tenantId), userId, cursorTime, cursorSessionId, limit);
    }

    /**
     * 游标查询有效消息。
     */
    public List<ChatMessageEntity> queryValidMessagesBefore(String tenantId, String userId, String sessionId,
                                                            Integer beforeSequence, int limit) {
        assertSessionAccess(tenantId, userId, sessionId, null);
        if (limit < 1 || limit > 101 || (beforeSequence != null && beforeSequence < 1)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "消息分页参数不合法");
        }
        return sessionRepository.queryValidMessagesBefore(blankToNull(tenantId), userId, sessionId,
                beforeSequence, limit);
    }

    /**
     * 软删除会话。
     */
    public int softDelete(String tenantId, String userId, String sessionId) {
        return sessionRepository.softDelete(blankToNull(tenantId), userId, sessionId);
    }

    /** 更新会话RAG设置。 */
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionEntity updateRagEnabled(String tenantId, String userId, String sessionId, boolean enabled) {
        return updateRagPolicy(tenantId, userId, sessionId,
                enabled ? SessionRagMode.AUTO : SessionRagMode.OFF, null);
    }

    /**
     * 以乐观锁更新会话RAG策略。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param mode RAG选择模式
     * @param expectedRevision 客户端期望版本，旧客户端可为空
     * @return 更新后的会话
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionEntity updateRagPolicy(String tenantId, String userId, String sessionId,
                                             SessionRagMode mode, Long expectedRevision) {
        return updateRagPolicy(tenantId, userId, sessionId, mode, null, expectedRevision);
    }

    /** 以同一 RAG revision 原子更新绑定模式和调用方式。 */
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionEntity updateRagPolicy(String tenantId, String userId, String sessionId,
                                             SessionRagMode mode, String requestedInvocationMode,
                                             Long expectedRevision) {
        if (mode == null || (expectedRevision != null && expectedRevision < 0)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG模式或版本不合法");
        }
        ChatSessionEntity session = lockSessionAccess(tenantId, userId, sessionId, null);
        RagInvocationMode invocationMode = requestedInvocationMode == null || requestedInvocationMode.isBlank()
                ? RagInvocationMode.resolve(session.getRagInvocationMode())
                : parseInvocationMode(requestedInvocationMode);
        long currentRevision = session.getRagRevision() == null ? 0L : session.getRagRevision();
        if (expectedRevision != null && expectedRevision != currentRevision) {
            // 前端携带版本时拒绝覆盖他人或另一标签页刚完成的设置。
            throw new AppException("SESSION_RAG_UPDATE_CONFLICT", "会话RAG设置已更新，请刷新后重试");
        }
        if (sessionRepository.updateRagPolicy(session.getTenantId(), session.getUserId(),
                session.getSessionId(), mode.name(), invocationMode.name(), mode.enabled(), currentRevision) != 1) {
            throw new AppException("SESSION_RAG_UPDATE_CONFLICT", "会话RAG设置更新失败，请刷新后重试");
        }
        return sessionRepository.querySession(session.getTenantId(), session.getUserId(), session.getSessionId());
    }

    /** 解析会话 RAG 调用方式，并将空值按兼容规则恢复为默认模式。 */
    private RagInvocationMode parseInvocationMode(String value) {
        try {
            return RagInvocationMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "RAG调用方式仅支持AUTO_CONTEXT或AGENT_TOOL");
        }
    }

    /**
     * 校验创建参数；不合法时抛出异常。
     */
    private void checkCreateCommand(CreateSessionCommandEntity command) {
        if (command == null
                || isBlank(command.getUserId())
                || isBlank(command.getSessionId())
                || isBlank(command.getAgentId())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID、会话ID和 Agent ID 不能为空");
        }
    }

    /**
     * 校验消息参数；不合法时抛出异常。
     */
    private void checkMessageCommand(AppendMessageCommandEntity command) {
        if (command == null
                || isBlank(command.getUserId())
                || isBlank(command.getSessionId())
                || isBlank(command.getRole())
                || isBlank(command.getContent())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID、会话ID、角色和内容不能为空");
        }
    }

    /**
     * 校验会话身份参数；不合法时抛出异常。
     */
    private void checkSessionIdentity(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID和会话ID不能为空");
        }
    }

    /**
     * 取非空默认值。
     */
    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    /**
     * 空白转空值。
     */
    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /**
     * 判断字符串为空。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 计算内容长度。
     */
    private Integer contentLength(String content) {
        return content == null ? 0 : content.length();
    }
}
