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
     * 游标查询用户会话；参数是可信身份、游标和数量；返回按最后活跃时间倒序的会话。
     */
    List<ChatSessionEntity> querySessions(String tenantId, String userId, LocalDateTime cursorTime,
                                          String cursorSessionId, int limit);

    /**
     * 更新最后消息时间；参数是租户、用户、会话ID和时间；返回影响行数。
     */
    int updateLastMessageTime(String tenantId, String userId, String sessionId, LocalDateTime lastMessageTime);

    /**
     * 按版本更新会话RAG策略。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param ragMode RAG模式
     * @param enabled 兼容开关
     * @param expectedRevision 期望策略版本
     * @return 影响行数
     */
    int updateRagPolicy(String tenantId, String userId, String sessionId, String ragMode,
                        boolean enabled, long expectedRevision);

    /**
     * 查询最大消息序号；参数是租户、用户和会话ID；返回当前最大序号。
     */
    Integer queryMaxSequenceNo(String tenantId, String userId, String sessionId);

    /**
     * 查询有效消息最大序号；参数是租户、用户和会话ID；返回有效上下文边界。
     */
    Integer queryMaxValidSequenceNo(String tenantId, String userId, String sessionId);

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

    /** 按可信复合范围查询单条有效消息。 */
    ChatMessageEntity queryValidMessage(String tenantId, String userId, String sessionId, String messageId);

    /**
     * 查询会话有效消息；参数是可信身份和会话；返回按序消息。
     */
    List<ChatMessageEntity> queryValidMessages(String tenantId, String userId, String sessionId);

    /**
     * 游标查询会话有效消息；参数是可信身份、会话、前序号和数量；返回倒序消息。
     */
    List<ChatMessageEntity> queryValidMessagesBefore(String tenantId, String userId, String sessionId,
                                                     Integer beforeSequence, int limit);

    /**
     * 软删除会话；参数是可信身份和会话；返回影响行数。
     */
    int softDelete(String tenantId, String userId, String sessionId);
}
