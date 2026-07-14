package cn.bugstack.ai.domain.session.adapter.repository;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 推进上下文版本；参数是可信会话身份；返回新版本。
     */
    long incrementContextRevision(String tenantId, String userId, String sessionId);

    /**
     * 失效运行消息；参数是可信身份、运行和原因；返回影响行数。
     */
    int invalidateRunMessages(String tenantId, String userId, String sessionId, String runId, String reason,
                              LocalDateTime invalidatedAt);

    /**
     * 查询运行消息；参数是可信身份和运行；返回消息列表。
     */
    List<ChatMessageEntity> queryRunMessages(String tenantId, String userId, String sessionId, String runId);

    /**
     * 查询会话有效消息；参数是可信身份和会话；返回按序消息。
     */
    List<ChatMessageEntity> queryValidMessages(String tenantId, String userId, String sessionId);
}
