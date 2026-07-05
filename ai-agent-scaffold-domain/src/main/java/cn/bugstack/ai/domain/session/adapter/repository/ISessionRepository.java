package cn.bugstack.ai.domain.session.adapter.repository;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;

import java.time.LocalDateTime;

public interface ISessionRepository {

    /**
     * 新增会话；参数是会话实体；返回影响行数。
     */
    int insertSession(ChatSessionEntity session);

    /**
     * 查询会话；参数是租户、用户和会话ID；返回会话实体。
     */
    ChatSessionEntity querySession(String tenantId, String userId, String sessionId);

    /**
     * 锁定会话；参数是租户、用户和会话ID；返回被锁定的会话实体。
     */
    ChatSessionEntity lockSession(String tenantId, String userId, String sessionId);

    /**
     * 更新最后消息时间；参数是租户、用户、会话ID和时间；返回影响行数。
     */
    int updateLastMessageTime(String tenantId, String userId, String sessionId, LocalDateTime lastMessageTime);

    /**
     * 查询最大消息序号；参数是租户、用户和会话ID；返回当前最大序号。
     */
    Integer queryMaxSequenceNo(String tenantId, String userId, String sessionId);

    /**
     * 新增消息；参数是消息实体；返回影响行数。
     */
    int insertMessage(ChatMessageEntity message);
}
