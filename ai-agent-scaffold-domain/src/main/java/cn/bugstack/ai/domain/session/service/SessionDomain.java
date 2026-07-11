package cn.bugstack.ai.domain.session.service;

import cn.bugstack.ai.domain.session.adapter.repository.ISessionRepository;
import cn.bugstack.ai.domain.context.service.CharacterTokenCounter;
import cn.bugstack.ai.domain.session.model.entity.AppendMessageCommandEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.model.entity.CreateSessionCommandEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class SessionDomain {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    private static final String STATUS_ACTIVE = "active";
    private static final String CONTENT_TYPE_TEXT = "text";
    private static final CharacterTokenCounter TOKEN_COUNTER = new CharacterTokenCounter();

    private final ISessionRepository sessionRepository;

    /**
     * 创建会话领域服务；参数是会话仓储；返回服务实例。
     */
    public SessionDomain(ISessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 创建平台会话；参数是会话创建命令；返回已保存的会话。
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
                .appName(command.getAppName())
                .title(blankToDefault(command.getTitle(), command.getAgentName()))
                .status(STATUS_ACTIVE)
                .lastMessageTime(now)
                .build();
        sessionRepository.insertSession(session);
        AiLog.info(AiLog.chat().sessionCreated(session.getTenantId(), session.getUserId(), session.getSessionId(),
                session.getAgentId(), session.getAgentName(), session.getAppName()));
        return session;
    }

    /**
     * 校验会话归属；参数是租户、用户、会话ID和 Agent ID；返回可访问的会话。
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
     * 保存用户消息；参数是租户、用户、会话ID、内容和链路ID；返回消息实体。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendUserMessage(String tenantId, String userId, String sessionId, String content, String traceId) {
        AppendMessageCommandEntity command = new AppendMessageCommandEntity();
        command.setTenantId(tenantId);
        command.setUserId(userId);
        command.setSessionId(sessionId);
        command.setRole(ROLE_USER);
        command.setContentType(CONTENT_TYPE_TEXT);
        command.setContent(content);
        command.setTraceId(traceId);
        return appendMessage(command);
    }

    /**
     * 保存助手消息；参数是租户、用户、会话ID、内容和链路ID；返回消息实体。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendAssistantMessage(String tenantId, String userId, String sessionId, String content, String traceId) {
        AppendMessageCommandEntity command = new AppendMessageCommandEntity();
        command.setTenantId(tenantId);
        command.setUserId(userId);
        command.setSessionId(sessionId);
        command.setRole(ROLE_ASSISTANT);
        command.setContentType(CONTENT_TYPE_TEXT);
        command.setContent(content);
        command.setTraceId(traceId);
        return appendMessage(command);
    }

    /**
     * 保存消息；参数是消息命令；返回已保存的消息。
     */
    private ChatMessageEntity appendMessage(AppendMessageCommandEntity command) {
        checkMessageCommand(command);
        String tenantId = blankToNull(command.getTenantId());
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
                .role(command.getRole())
                .contentType(command.getContentType())
                .content(command.getContent())
                .estimatedTokenCount(TOKEN_COUNTER.estimate(command.getContent()))
                .sequenceNo(maxSequenceNo == null ? 1 : maxSequenceNo + 1)
                .parentMessageId(command.getParentMessageId())
                .traceId(command.getTraceId())
                .build();
        sessionRepository.insertMessage(message);
        sessionRepository.updateLastMessageTime(session.getTenantId(), session.getUserId(), session.getSessionId(), now);
        AiLog.info(AiLog.chat().messageSaved(message.getTenantId(), message.getUserId(), message.getSessionId(),
                message.getMessageId(), message.getRole(), message.getSequenceNo(), contentLength(message.getContent())));
        return message;
    }

    /**
     * 校验创建参数；参数是创建命令；非法时抛出异常。
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
     * 校验消息参数；参数是消息命令；非法时抛出异常。
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
     * 校验会话身份参数；参数是用户ID和会话ID；非法时抛出异常。
     */
    private void checkSessionIdentity(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID和会话ID不能为空");
        }
    }

    /**
     * 取非空默认值；参数是候选值和默认值；返回可展示文本。
     */
    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    /**
     * 空白转空值；参数是字符串；返回可落库字符串。
     */
    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /**
     * 判断字符串为空；参数是字符串；返回是否为空。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 计算内容长度；参数是内容；返回字符数。
     */
    private Integer contentLength(String content) {
        return content == null ? 0 : content.length();
    }
}
