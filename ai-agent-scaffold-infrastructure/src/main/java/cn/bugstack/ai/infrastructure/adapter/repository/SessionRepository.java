package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.session.adapter.repository.ISessionRepository;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.infrastructure.dao.IChatMessageDao;
import cn.bugstack.ai.infrastructure.dao.IChatSessionDao;
import cn.bugstack.ai.infrastructure.dao.po.ChatMessagePO;
import cn.bugstack.ai.infrastructure.dao.po.ChatSessionPO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
/** 会话与消息的 MySQL 仓储，所有读写都以租户、用户和会话范围为边界。 */
public class SessionRepository implements ISessionRepository {

    /** 会话元数据、RAG 策略和上下文版本的持久化入口。 */
    private final IChatSessionDao chatSessionDao;
    /** 会话消息、有效性和顺序边界的持久化入口。 */
    private final IChatMessageDao chatMessageDao;

    /** 注入会话和消息 DAO，仓储本身不持有跨请求状态。 */
    public SessionRepository(IChatSessionDao chatSessionDao, IChatMessageDao chatMessageDao) {
        this.chatSessionDao = chatSessionDao;
        this.chatMessageDao = chatMessageDao;
    }

    /** 新增会话，并把空 revision 和历史 RAG 调用方式归一为当前数据库值。 */
    @Override
    public int insertSession(ChatSessionEntity session) {
        return chatSessionDao.insert(toSessionPO(session));
    }

    /** 在可信租户和用户范围内查询单个会话。 */
    @Override
    public ChatSessionEntity querySession(String tenantId, String userId, String sessionId) {
        return toSessionEntity(chatSessionDao.queryByTenantUserSession(tenantId, userId, sessionId));
    }

    /** 锁定归属范围内的会话，供需要串行修改会话状态的事务使用。 */
    @Override
    public ChatSessionEntity lockSession(String tenantId, String userId, String sessionId) {
        return toSessionEntity(chatSessionDao.lockByTenantUserSession(tenantId, userId, sessionId));
    }

    @Override
    /** 使用时间和会话 ID 组成稳定游标，分页查询用户会话。 */
    public List<ChatSessionEntity> querySessions(String tenantId, String userId, LocalDateTime cursorTime,
                                                 String cursorSessionId, int limit) {
        return chatSessionDao.queryPage(tenantId, userId, cursorTime, cursorSessionId, limit).stream()
                .map(this::toSessionEntity).collect(Collectors.toList());
    }

    /** 更新最后消息时间，为会话列表排序提供依据。 */
    @Override
    public int updateLastMessageTime(String tenantId, String userId, String sessionId, LocalDateTime lastMessageTime) {
        return chatSessionDao.updateLastMessageTime(tenantId, userId, sessionId, lastMessageTime);
    }

    @Override
    /** 使用期望 revision 更新本会话后续轮次采用的 RAG 策略。 */
    public int updateRagPolicy(String tenantId, String userId, String sessionId, String ragMode,
                               String ragInvocationMode, boolean enabled, long expectedRevision) {
        return chatSessionDao.updateRagPolicy(tenantId, userId, sessionId, ragMode, ragInvocationMode,
                enabled, expectedRevision);
    }

    /** 查询包含失效记录在内的最大消息序号，保证后续写入序号不重复。 */
    @Override
    public Integer queryMaxSequenceNo(String tenantId, String userId, String sessionId) {
        return chatMessageDao.queryMaxSequenceNo(tenantId, userId, sessionId);
    }

    /** 查询仍可进入上下文的最大消息序号，用作有效历史边界。 */
    @Override
    public Integer queryMaxValidSequenceNo(String tenantId, String userId, String sessionId) {
        return chatMessageDao.queryMaxValidSequenceNo(tenantId, userId, sessionId);
    }

    /** 写入消息及其运行、有效性、顺序和追踪信息。 */
    @Override
    public int insertMessage(ChatMessageEntity message) {
        return chatMessageDao.insert(toMessagePO(message));
    }

    @Override
    /** 原子递增上下文版本后回读新值，使缓存键立即与旧上下文分离。 */
    public long incrementContextRevision(String tenantId, String userId, String sessionId) {
        chatSessionDao.incrementContextRevision(tenantId, userId, sessionId);
        ChatSessionPO session = chatSessionDao.queryByTenantUserSession(tenantId, userId, sessionId);
        return session == null || session.getContextRevision() == null ? 0L : session.getContextRevision();
    }

    @Override
    /** 将指定运行产生的消息整体标记为无效，保留记录用于审计和重放判断。 */
    public int invalidateRunMessages(String tenantId, String userId, String sessionId, String runId, String reason,
                                     LocalDateTime invalidatedAt) {
        return chatMessageDao.invalidateRunMessages(tenantId, userId, sessionId, runId, reason, invalidatedAt);
    }

    @Override
    /** 查询一次运行产生的全部消息，包括之后可能被标记无效的记录。 */
    public List<ChatMessageEntity> queryRunMessages(String tenantId, String userId, String sessionId, String runId) {
        return chatMessageDao.queryRunMessages(tenantId, userId, sessionId, runId).stream()
                .map(this::toMessageEntity)
                .collect(Collectors.toList());
    }

    @Override
    /** 仅查询当前仍有效且属于指定会话的消息。 */
    public ChatMessageEntity queryValidMessage(String tenantId, String userId, String sessionId, String messageId) {
        return toMessageEntity(chatMessageDao.queryValidMessage(tenantId, userId, sessionId, messageId));
    }

    @Override
    /** 按消息顺序查询会话当前全部有效上下文。 */
    public List<ChatMessageEntity> queryValidMessages(String tenantId, String userId, String sessionId) {
        return chatMessageDao.queryValidMessages(tenantId, userId, sessionId).stream()
                .map(this::toMessageEntity).collect(Collectors.toList());
    }

    @Override
    /** 在给定序号之前倒序取一页有效消息，供上下文窗口分批装载。 */
    public List<ChatMessageEntity> queryValidMessagesBefore(String tenantId, String userId, String sessionId,
                                                            Integer beforeSequence, int limit) {
        return chatMessageDao.queryValidMessagesBefore(tenantId, userId, sessionId, beforeSequence, limit).stream()
                .map(this::toMessageEntity).collect(Collectors.toList());
    }

    @Override
    /** 在归属范围内软删除会话，保留历史数据但从正常查询中隐藏。 */
    public int softDelete(String tenantId, String userId, String sessionId) {
        return chatSessionDao.softDelete(tenantId, userId, sessionId);
    }

    /** 将会话领域状态转换为数据库记录，并补齐兼容字段默认值。 */
    private ChatSessionPO toSessionPO(ChatSessionEntity session) {
        return ChatSessionPO.builder()
                .tenantId(session.getTenantId())
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .agentId(session.getAgentId())
                .agentName(session.getAgentName())
                .sourceType(session.getSourceType())
                .workflowVersion(session.getWorkflowVersion())
                .modelCode(session.getModelCode())
                .appName(session.getAppName())
                .title(session.getTitle())
                .status(session.getStatus())
                .ragEnabled(Boolean.TRUE.equals(session.getRagEnabled()))
                .ragMode(session.getRagMode())
                .ragInvocationMode(cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode
                        .resolve(session.getRagInvocationMode()).name())
                .ragRevision(session.getRagRevision() == null ? 0L : session.getRagRevision())
                .lastMessageTime(session.getLastMessageTime())
                .contextRevision(session.getContextRevision() == null ? 0L : session.getContextRevision())
                .build();
    }

    /** 从数据库恢复会话，并兼容旧记录缺少来源类型或 RAG 字段的情况。 */
    private ChatSessionEntity toSessionEntity(ChatSessionPO session) {
        if (session == null) {
            return null;
        }
        return ChatSessionEntity.builder()
                .tenantId(session.getTenantId())
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .agentId(session.getAgentId())
                .agentName(session.getAgentName())
                .sourceType(session.getSourceType() == null || session.getSourceType().isBlank() ? "agent" : session.getSourceType())
                .workflowVersion(session.getWorkflowVersion())
                .modelCode(session.getModelCode())
                .appName(session.getAppName())
                .title(session.getTitle())
                .status(session.getStatus())
                .ragEnabled(Boolean.TRUE.equals(session.getRagEnabled()))
                .ragMode(session.getRagMode())
                .ragInvocationMode(cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode
                        .resolve(session.getRagInvocationMode()).name())
                .ragRevision(session.getRagRevision() == null ? 0L : session.getRagRevision())
                .lastMessageTime(session.getLastMessageTime())
                .contextRevision(session.getContextRevision() == null ? 0L : session.getContextRevision())
                .build();
    }

    /** 将消息正文、运行归属、有效性和顺序信息转换为数据库记录。 */
    private ChatMessagePO toMessagePO(ChatMessageEntity message) {
        return ChatMessagePO.builder()
                .tenantId(message.getTenantId())
                .userId(message.getUserId())
                .sessionId(message.getSessionId())
                .messageId(message.getMessageId())
                .runId(message.getRunId())
                .validityStatus(message.getValidityStatus())
                .invalidReason(message.getInvalidReason())
                .invalidatedAt(message.getInvalidatedAt())
                .role(message.getRole())
                .contentType(message.getContentType())
                .content(message.getContent())
                .estimatedTokenCount(message.getEstimatedTokenCount())
                .sequenceNo(message.getSequenceNo())
                .parentMessageId(message.getParentMessageId())
                .traceId(message.getTraceId())
                .metadata(message.getMetadata())
                .build();
    }

    /** 从数据库记录恢复可供领域层读取的完整消息。 */
    private ChatMessageEntity toMessageEntity(ChatMessagePO message) {
        return ChatMessageEntity.builder()
                .tenantId(message.getTenantId()).userId(message.getUserId()).sessionId(message.getSessionId())
                .messageId(message.getMessageId()).runId(message.getRunId()).validityStatus(message.getValidityStatus())
                .invalidReason(message.getInvalidReason()).invalidatedAt(message.getInvalidatedAt())
                .role(message.getRole()).contentType(message.getContentType()).content(message.getContent())
                .estimatedTokenCount(message.getEstimatedTokenCount()).sequenceNo(message.getSequenceNo())
                .parentMessageId(message.getParentMessageId()).traceId(message.getTraceId())
                .metadata(message.getMetadata())
                .createTime(message.getCreateTime()).build();
    }
}
