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

@Repository
public class SessionRepository implements ISessionRepository {

    private final IChatSessionDao chatSessionDao;
    private final IChatMessageDao chatMessageDao;

    /**
     * 创建会话仓储；参数是会话和消息 DAO；返回仓储实例。
     */
    public SessionRepository(IChatSessionDao chatSessionDao, IChatMessageDao chatMessageDao) {
        this.chatSessionDao = chatSessionDao;
        this.chatMessageDao = chatMessageDao;
    }

    /**
     * 新增会话；参数是会话实体；返回影响行数。
     */
    @Override
    public int insertSession(ChatSessionEntity session) {
        return chatSessionDao.insert(toSessionPO(session));
    }

    /**
     * 查询会话；参数是租户、用户和会话ID；返回会话实体。
     */
    @Override
    public ChatSessionEntity querySession(String tenantId, String userId, String sessionId) {
        return toSessionEntity(chatSessionDao.queryByTenantUserSession(tenantId, userId, sessionId));
    }

    /**
     * 锁定会话；参数是租户、用户和会话ID；返回被锁定的会话实体。
     */
    @Override
    public ChatSessionEntity lockSession(String tenantId, String userId, String sessionId) {
        return toSessionEntity(chatSessionDao.lockByTenantUserSession(tenantId, userId, sessionId));
    }

    /**
     * 更新最后消息时间；参数是租户、用户、会话ID和时间；返回影响行数。
     */
    @Override
    public int updateLastMessageTime(String tenantId, String userId, String sessionId, LocalDateTime lastMessageTime) {
        return chatSessionDao.updateLastMessageTime(tenantId, userId, sessionId, lastMessageTime);
    }

    /**
     * 查询最大消息序号；参数是租户、用户和会话ID；返回当前最大序号。
     */
    @Override
    public Integer queryMaxSequenceNo(String tenantId, String userId, String sessionId) {
        return chatMessageDao.queryMaxSequenceNo(tenantId, userId, sessionId);
    }

    /**
     * 新增消息；参数是消息实体；返回影响行数。
     */
    @Override
    public int insertMessage(ChatMessageEntity message) {
        return chatMessageDao.insert(toMessagePO(message));
    }

    /**
     * 转换会话持久化对象；参数是会话实体；返回会话 PO。
     */
    private ChatSessionPO toSessionPO(ChatSessionEntity session) {
        return ChatSessionPO.builder()
                .tenantId(session.getTenantId())
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .agentId(session.getAgentId())
                .agentName(session.getAgentName())
                .appName(session.getAppName())
                .title(session.getTitle())
                .status(session.getStatus())
                .lastMessageTime(session.getLastMessageTime())
                .build();
    }

    /**
     * 转换会话实体；参数是会话 PO；返回领域会话实体。
     */
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
                .appName(session.getAppName())
                .title(session.getTitle())
                .status(session.getStatus())
                .lastMessageTime(session.getLastMessageTime())
                .build();
    }

    /**
     * 转换消息持久化对象；参数是消息实体；返回消息 PO。
     */
    private ChatMessagePO toMessagePO(ChatMessageEntity message) {
        return ChatMessagePO.builder()
                .tenantId(message.getTenantId())
                .userId(message.getUserId())
                .sessionId(message.getSessionId())
                .messageId(message.getMessageId())
                .role(message.getRole())
                .contentType(message.getContentType())
                .content(message.getContent())
                .estimatedTokenCount(message.getEstimatedTokenCount())
                .sequenceNo(message.getSequenceNo())
                .parentMessageId(message.getParentMessageId())
                .traceId(message.getTraceId())
                .build();
    }
}
